package com.litemusic.app.data

import com.litemusic.shared.api.TogetherActionResponse
import com.litemusic.shared.api.TogetherRoomInfo
import com.litemusic.shared.api.TogetherStatusData
import com.litemusic.shared.api.TogetherStatusResponse
import com.litemusic.shared.player.PlayerStateMachine
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherRepositoryTest {
    @Test
    fun repositoryAcceptsASessionIdentitySource() {
        assertTrue(
            TogetherRepository::class.java.constructors.any { constructor ->
                constructor.parameterTypes.any { it.name == "kotlinx.coroutines.flow.Flow" }
            },
        )
    }

    @Test
    fun lateRefreshDoesNotReplaceTheNewerRoomState() = runBlocking {
        val older = CompletableDeferred<AppResult<TogetherStatusResponse>>()
        val newer = CompletableDeferred<AppResult<TogetherStatusResponse>>()
        val remote = DeferredTogetherRemote(statuses = ArrayDeque(listOf(older, newer)))
        val repository = TogetherRepository(remote, PlayerStateMachine())

        try {
            val oldRefresh = async { repository.refresh() }
            yield()
            val newRefresh = async { repository.refresh() }
            yield()
            newer.complete(roomStatus("new-room"))
            assertTrue(newRefresh.await().ok)
            older.complete(roomStatus("old-room"))
            oldRefresh.await()

            assertEquals("new-room", repository.room.value.code)
        } finally {
            older.complete(roomStatus(""))
            newer.complete(roomStatus(""))
            repository.stopPolling()
        }
    }

    @Test
    fun rejectedLeaveKeepsTheCurrentRoom() = runBlocking {
        val remote = DeferredTogetherRemote(
            statuses = ArrayDeque(listOf(CompletableDeferred(roomStatus("room-1")))),
            leaveResult = AppResult.Success(TogetherActionResponse(code = 500, message = "不能退出")),
        )
        val repository = TogetherRepository(remote, PlayerStateMachine())
        try {
            repository.refresh()

            val outcome = repository.leave()

            assertFalse(outcome.ok)
            assertEquals("room-1", repository.room.value.code)
            assertTrue(repository.room.value.inRoom)
        } finally {
            repository.stopPolling()
        }
    }

    @Test
    fun nonSuccessStatusDoesNotClearTheExistingRoom() = runBlocking {
        val remote = DeferredTogetherRemote(
            statuses = ArrayDeque(
                listOf(
                    CompletableDeferred(roomStatus("room-1")),
                    CompletableDeferred(AppResult.Success(TogetherStatusResponse(code = 503, message = "暂不可用"))),
                ),
            ),
        )
        val repository = TogetherRepository(remote, PlayerStateMachine())
        try {
            repository.refresh()

            val outcome = repository.refresh()

            assertFalse(outcome.ok)
            assertEquals("room-1", repository.room.value.code)
            assertEquals("暂不可用", repository.room.value.error)
        } finally {
            repository.stopPolling()
        }
    }

    @Test
    fun stoppedLifecycleRejectsAnInFlightRefreshFromAnotherThread() = runBlocking {
        val pending = CompletableDeferred<AppResult<TogetherStatusResponse>>()
        val remote = DeferredTogetherRemote(statuses = ArrayDeque(listOf(pending)))
        val repository = TogetherRepository(remote, PlayerStateMachine())

        try {
            val refresh = async(Dispatchers.Default) { repository.refresh() }
            withTimeout(1_000) { remote.statusStarted.await() }
            repository.stopPolling()
            pending.complete(roomStatus("old-room"))

            assertFalse(refresh.await().ok)
            assertFalse(repository.room.value.inRoom)
        } finally {
            pending.complete(roomStatus(""))
            repository.stopPolling()
        }
    }

    @Test
    fun releasingAnOlderPollingOwnerDoesNotInvalidateTheNewOwnersRefresh() = runBlocking {
        val oldResponse = CompletableDeferred<AppResult<TogetherStatusResponse>>()
        val newResponse = CompletableDeferred<AppResult<TogetherStatusResponse>>()
        val remote = DeferredTogetherRemote(statuses = ArrayDeque(listOf(oldResponse, newResponse)))
        val repository = TogetherRepository(remote, PlayerStateMachine())
        val oldOwner = Any()
        val newOwner = Any()

        try {
            repository.startPolling(7L, oldOwner)
            remote.statusStarted.await()
            repository.startPolling(7L, newOwner)
            withTimeout(1_000) { while (remote.statusCalls.get() < 2) yield() }

            repository.stopPolling(oldOwner)
            repository.clearForAccountChange(oldOwner)
            newResponse.complete(roomStatus("new-room"))
            withTimeout(1_000) { while (repository.room.value.code != "new-room") yield() }

            assertEquals("new-room", repository.room.value.code)
        } finally {
            oldResponse.complete(roomStatus(""))
            newResponse.complete(roomStatus(""))
            repository.stopPolling()
        }
    }

    @Test
    fun stoppingItsOwnPollingOwnerRejectsItsInFlightRefresh() = runBlocking {
        val pending = CompletableDeferred<AppResult<TogetherStatusResponse>>()
        val remote = DeferredTogetherRemote(statuses = ArrayDeque(listOf(pending)))
        val repository = TogetherRepository(remote, PlayerStateMachine())
        val owner = Any()

        try {
            repository.startPolling(7L, owner)
            remote.statusStarted.await()
            repository.stopPolling(owner)
            pending.complete(roomStatus("old-room"))
            remote.statusFinished.await()

            assertFalse(repository.room.value.inRoom)
        } finally {
            pending.complete(roomStatus(""))
            repository.stopPolling()
        }
    }

    @Test
    fun accountChangeFromAStoppedOwnerClearsCachedRoomWhenNoOwnerIsActive() = runBlocking {
        val remote = DeferredTogetherRemote(
            statuses = ArrayDeque(listOf(CompletableDeferred(roomStatus("account-a")))),
        )
        val repository = TogetherRepository(remote, PlayerStateMachine())
        val owner = Any()

        try {
            repository.startPolling(1L, owner)
            withTimeout(1_000) { while (repository.room.value.code != "account-a") yield() }
            repository.stopPolling(owner)

            repository.clearForAccountChange(owner)

            assertFalse(repository.room.value.inRoom)
            assertEquals("", repository.room.value.code)
        } finally {
            repository.stopPolling()
        }
    }

    @Test
    fun newPollingOwnerForAnotherAccountClearsThePreviousAccountsRoom() = runBlocking {
        val secondAccount = CompletableDeferred<AppResult<TogetherStatusResponse>>()
        val remote = DeferredTogetherRemote(
            statuses = ArrayDeque(listOf(CompletableDeferred(roomStatus("account-a")), secondAccount)),
        )
        val repository = TogetherRepository(remote, PlayerStateMachine())
        val firstOwner = Any()
        val secondOwner = Any()

        try {
            repository.startPolling(1L, firstOwner)
            withTimeout(1_000) { while (repository.room.value.code != "account-a") yield() }

            repository.startPolling(2L, secondOwner)
            withTimeout(1_000) { while (remote.statusCalls.get() < 2) yield() }
            assertFalse(repository.room.value.inRoom)

            secondAccount.complete(roomStatus("account-b"))
            withTimeout(1_000) { while (repository.room.value.code != "account-b") yield() }
            assertEquals("account-b", repository.room.value.code)
        } finally {
            secondAccount.complete(roomStatus(""))
            repository.stopPolling()
        }
    }

    @Test
    fun successfulLeavePreventsAnOlderStatusResponseFromRestoringTheRoom() = runBlocking {
        val oldStatus = CompletableDeferred<AppResult<TogetherStatusResponse>>()
        val remote = DeferredTogetherRemote(
            statuses = ArrayDeque(listOf(CompletableDeferred(roomStatus("room-1")), oldStatus)),
        )
        val repository = TogetherRepository(remote, PlayerStateMachine())
        try {
            repository.refresh()
            val refresh = async(Dispatchers.Default) { repository.refresh() }
            withTimeout(1_000) { while (remote.statusCalls.get() < 2) yield() }

            assertTrue(repository.leave().ok)
            oldStatus.complete(roomStatus("old-room"))

            assertFalse(refresh.await().ok)
            assertFalse(repository.room.value.inRoom)
        } finally {
            oldStatus.complete(roomStatus(""))
            repository.stopPolling()
        }
    }

    @Test
    fun startingPollingForAnotherAccountClearsThePreviousAccountsRoom() = runBlocking {
        val secondAccount = CompletableDeferred<AppResult<TogetherStatusResponse>>()
        val remote = DeferredTogetherRemote(
            statuses = ArrayDeque(listOf(CompletableDeferred(roomStatus("account-a")), secondAccount)),
        )
        val repository = TogetherRepository(remote, PlayerStateMachine())
        try {
            repository.startPolling(1L)
            withTimeout(1_000) { while (repository.room.value.code != "account-a") yield() }
            repository.stopPolling()

            repository.startPolling(2L)
            withTimeout(1_000) { while (remote.statusCalls.get() < 2) yield() }
            assertFalse(repository.room.value.inRoom)

            secondAccount.complete(roomStatus("account-b"))
            withTimeout(1_000) { while (repository.room.value.code != "account-b") yield() }
        } finally {
            secondAccount.complete(roomStatus(""))
            repository.stopPolling()
        }
    }

    @Test
    fun lateLeaveForAnOlderRoomDoesNotClearANewerRoom() = runBlocking {
        val leaveResponse = CompletableDeferred<AppResult<TogetherActionResponse>>()
        val remote = DeferredTogetherRemote(
            statuses = ArrayDeque(
                listOf(
                    CompletableDeferred(roomStatus("room-a")),
                    CompletableDeferred(roomStatus("room-b")),
                ),
            ),
            leaveDeferred = leaveResponse,
        )
        val repository = TogetherRepository(remote, PlayerStateMachine())
        try {
            repository.refresh()
            val leave = async(Dispatchers.Default) { repository.leave() }
            remote.leaveStarted.await()

            assertTrue(repository.refresh().ok)
            assertEquals("room-b", repository.room.value.code)
            leaveResponse.complete(AppResult.Success(TogetherActionResponse(code = 200)))

            assertFalse(leave.await().ok)
            assertEquals("room-b", repository.room.value.code)
        } finally {
            leaveResponse.complete(AppResult.Success(TogetherActionResponse(code = 200)))
            repository.stopPolling()
        }
    }

    @Test
    fun boundAccountChangeClearsStoppedAccountsRoomBeforeANewFailure() = runBlocking {
        val account = MutableStateFlow(1L)
        val remote = DeferredTogetherRemote(
            statuses = ArrayDeque(
                listOf(
                    CompletableDeferred(roomStatus("account-a")),
                    CompletableDeferred(AppResult.Failure(503, "账号 B 暂不可用")),
                ),
            ),
        )
        val repository = TogetherRepository(remote, PlayerStateMachine(), account)
        val owner = Any()

        try {
            // The page can become visible before the repository's collector sees DataStore.
            repository.startPolling(0L, owner)
            withTimeout(1_000) { while (repository.room.value.code != "account-a") yield() }
            repository.stopPolling(owner)

            account.value = 2L
            withTimeout(1_000) { while (repository.room.value.inRoom) yield() }
            val outcome = repository.refresh()

            assertFalse(outcome.ok)
            assertEquals("", repository.room.value.code)
            assertFalse(repository.room.value.inRoom)
        } finally {
            repository.stopPolling()
        }
    }

    @Test
    fun boundAccountChangeRejectsAnOldInFlightResponseBeforeStartingTheNewOwner() = runBlocking {
        val account = MutableStateFlow(1L)
        val oldResponse = CompletableDeferred<AppResult<TogetherStatusResponse>>()
        val currentResponse = CompletableDeferred<AppResult<TogetherStatusResponse>>()
        val remote = DeferredTogetherRemote(statuses = ArrayDeque(listOf(oldResponse, currentResponse)))
        val repository = TogetherRepository(remote, PlayerStateMachine(), account)
        val owner = Any()

        try {
            val oldRefresh = async { repository.refresh() }
            withTimeout(1_000) { remote.statusStarted.await() }

            account.value = 2L
            withTimeout(1_000) { while (repository.room.value.isRefreshing) yield() }
            oldResponse.complete(roomStatus("account-a"))
            val staleResult = withTimeout(1_000) { oldRefresh.await() }

            // The old response has returned through the repository before this new owner starts.
            assertFalse(staleResult.ok)
            assertTrue(staleResult.isStale)
            repository.startPolling(1L, owner)
            withTimeout(1_000) { while (remote.statusCalls.get() < 2) yield() }
            currentResponse.complete(roomStatus("account-b"))
            withTimeout(1_000) { while (repository.room.value.code != "account-b") yield() }

            assertEquals("account-b", repository.room.value.code)
        } finally {
            oldResponse.complete(roomStatus(""))
            currentResponse.complete(roomStatus(""))
            repository.stopPolling()
        }
    }

    @Test
    fun boundLogoutClearsCacheAndRefusesStandaloneRefresh() = runBlocking {
        val account = MutableStateFlow(1L)
        val remote = DeferredTogetherRemote(statuses = ArrayDeque(listOf(CompletableDeferred(roomStatus("account-a")))))
        val repository = TogetherRepository(remote, PlayerStateMachine(), account)
        val owner = Any()

        try {
            repository.startPolling(0L, owner)
            withTimeout(1_000) { while (repository.room.value.code != "account-a") yield() }
            repository.stopPolling(owner)

            account.value = 0L
            withTimeout(1_000) { while (repository.room.value.inRoom) yield() }
            val outcome = repository.refresh()

            assertFalse(outcome.ok)
            assertEquals("请先登录", outcome.message)
            assertEquals(1, remote.statusCalls.get())
        } finally {
            repository.stopPolling()
        }
    }

    @Test
    fun standaloneRefreshWaitsForTheFirstBoundIdentityBeforeCallingStatus() = runBlocking {
        val account = MutableStateFlow<Long?>(null)
        val remote = DeferredTogetherRemote(statuses = ArrayDeque(listOf(CompletableDeferred(roomStatus("current-room")))))
        val repository = TogetherRepository(
            remote,
            PlayerStateMachine(),
            account.filterNotNull(),
        )

        try {
            val refresh = async { repository.refresh() }
            yield()
            assertEquals(0, remote.statusCalls.get())

            account.value = 7L

            assertTrue(withTimeout(1_000) { refresh.await() }.ok)
            assertEquals("current-room", repository.room.value.code)
        } finally {
            repository.stopPolling()
        }
    }

    @Test
    fun legacyZeroUidStillClearsTheCachedRoom() = runBlocking {
        val remote = DeferredTogetherRemote(statuses = ArrayDeque(listOf(CompletableDeferred(roomStatus("legacy-room")))))
        val repository = TogetherRepository(remote, PlayerStateMachine())
        val owner = Any()

        try {
            repository.startPolling(7L, owner)
            withTimeout(1_000) { while (repository.room.value.code != "legacy-room") yield() }

            repository.startPolling(0L, owner)

            assertFalse(repository.room.value.inRoom)
            assertEquals("请先登录", repository.room.value.error)
        } finally {
            repository.stopPolling()
        }
    }

    @Test
    fun latestVisibleOwnerStartsAfterFirstIdentityWhenOlderOwnerStops() = runBlocking {
        val account = MutableStateFlow<Long?>(null)
        val remote = DeferredTogetherRemote(statuses = ArrayDeque(listOf(CompletableDeferred(roomStatus("owner-b-room")))))
        val repository = TogetherRepository(remote, PlayerStateMachine(), account.filterNotNull())
        val oldOwner = Any()
        val visibleOwner = Any()

        try {
            repository.startPolling(0L, oldOwner)
            repository.startPolling(0L, visibleOwner)
            repository.stopPolling(oldOwner)

            account.value = 9L

            withTimeout(1_000) { while (repository.room.value.code != "owner-b-room") yield() }
            assertEquals("owner-b-room", repository.room.value.code)
        } finally {
            repository.stopPolling()
        }
    }

    @Test
    fun latestVisibleOwnerRestartsAfterLogoutAndRelogin() = runBlocking {
        val account = MutableStateFlow(0L)
        val remote = DeferredTogetherRemote(statuses = ArrayDeque(listOf(CompletableDeferred(roomStatus("relogin-room")))))
        val repository = TogetherRepository(remote, PlayerStateMachine(), account)
        val oldOwner = Any()
        val visibleOwner = Any()

        try {
            repository.startPolling(0L, oldOwner)
            repository.startPolling(0L, visibleOwner)
            repository.stopPolling(oldOwner)

            account.value = 9L

            withTimeout(1_000) { while (repository.room.value.code != "relogin-room") yield() }
            assertEquals("relogin-room", repository.room.value.code)
        } finally {
            repository.stopPolling()
        }
    }

    @Test
    fun boundAccountChangeDropsAnOlderInvitesResult() = runBlocking {
        val account = MutableStateFlow(1L)
        val inviteResponse = CompletableDeferred<AppResult<TogetherActionResponse>>()
        val remote = DeferredTogetherRemote(
            statuses = ArrayDeque(
                listOf(
                    CompletableDeferred(roomStatus("account-a")),
                    CompletableDeferred(noRoomStatus()),
                ),
            ),
            inviteDeferred = inviteResponse,
        )
        val repository = TogetherRepository(remote, PlayerStateMachine(), account)
        val owner = Any()

        try {
            repository.startPolling(0L, owner)
            withTimeout(1_000) { while (repository.room.value.code != "account-a") yield() }
            val invite = async { repository.sendInvite(2L) }
            withTimeout(1_000) { remote.inviteStarted.await() }

            account.value = 2L
            withTimeout(1_000) { while (remote.statusCalls.get() < 2) yield() }
            inviteResponse.complete(AppResult.Success(TogetherActionResponse(code = 200)))

            val outcome = withTimeout(1_000) { invite.await() }
            assertFalse(outcome.ok)
            assertEquals("邀请结果已过期", outcome.message)
        } finally {
            inviteResponse.complete(AppResult.Success(TogetherActionResponse(code = 200)))
            repository.stopPolling()
        }
    }

    @Test
    fun boundAccountChangeDropsAnOlderLeaveFailure() = runBlocking {
        val account = MutableStateFlow(1L)
        val leaveResponse = CompletableDeferred<AppResult<TogetherActionResponse>>()
        val remote = DeferredTogetherRemote(
            statuses = ArrayDeque(
                listOf(
                    CompletableDeferred(roomStatus("account-a")),
                    CompletableDeferred(noRoomStatus()),
                ),
            ),
            leaveDeferred = leaveResponse,
        )
        val repository = TogetherRepository(remote, PlayerStateMachine(), account)
        val owner = Any()

        try {
            repository.startPolling(0L, owner)
            withTimeout(1_000) { while (repository.room.value.code != "account-a") yield() }
            val leave = async { repository.leave() }
            withTimeout(1_000) { remote.leaveStarted.await() }

            account.value = 2L
            withTimeout(1_000) { while (remote.statusCalls.get() < 2) yield() }
            leaveResponse.complete(AppResult.Failure(500, "账号 A 无法退出"))

            val outcome = withTimeout(1_000) { leave.await() }
            assertFalse(outcome.ok)
            assertEquals("退出结果已过期", outcome.message)
        } finally {
            leaveResponse.complete(AppResult.Failure(500, "账号 A 无法退出"))
            repository.stopPolling()
        }
    }

    private fun roomStatus(roomId: String): AppResult<TogetherStatusResponse> =
        AppResult.Success(
            TogetherStatusResponse(
                code = 200,
                data = TogetherStatusData(
                    inRoom = true,
                    roomInfo = TogetherRoomInfo(roomId = roomId),
                ),
            ),
        )

    private fun noRoomStatus(): AppResult<TogetherStatusResponse> =
        AppResult.Success(TogetherStatusResponse(code = 200, data = TogetherStatusData(inRoom = false)))
}

private class DeferredTogetherRemote(
    private val statuses: ArrayDeque<CompletableDeferred<AppResult<TogetherStatusResponse>>>,
    private val leaveResult: AppResult<TogetherActionResponse> = AppResult.Success(TogetherActionResponse(code = 200)),
    private val leaveDeferred: CompletableDeferred<AppResult<TogetherActionResponse>>? = null,
    private val inviteDeferred: CompletableDeferred<AppResult<TogetherActionResponse>>? = null,
) : TogetherRemote {
    val statusStarted = CompletableDeferred<Unit>()
    val statusFinished = CompletableDeferred<Unit>()
    val statusCalls = AtomicInteger()
    val leaveStarted = CompletableDeferred<Unit>()
    val inviteStarted = CompletableDeferred<Unit>()

    override suspend fun status(): AppResult<TogetherStatusResponse> =
        withContext(NonCancellable) {
            statusStarted.complete(Unit)
            val response = statuses.removeFirst()
            statusCalls.incrementAndGet()
            response.await().also { statusFinished.complete(Unit) }
        }

    override suspend fun sendInvite(roomId: String, acceptorId: Long): AppResult<TogetherActionResponse> =
        withContext(NonCancellable) {
            inviteStarted.complete(Unit)
            inviteDeferred?.await() ?: AppResult.Success(TogetherActionResponse(code = 200))
        }

    override suspend fun leave(roomId: String): AppResult<TogetherActionResponse> =
        withContext(NonCancellable) {
            leaveStarted.complete(Unit)
            leaveDeferred?.await() ?: leaveResult
        }
}
