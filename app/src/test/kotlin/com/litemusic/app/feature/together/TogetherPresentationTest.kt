package com.litemusic.app.feature.together

import com.litemusic.app.data.TogetherRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherPresentationTest {
    @Test
    fun operationExecutesOnceWhenStateChangesWhileItIsWaiting() = runBlocking {
        val state = MutableStateFlow(TogetherViewModel.UiState(myUid = 7L))
        val response = CompletableDeferred<TogetherRepository.OperationResult>()
        val calls = AtomicInteger()

        val publish = async {
            publishTogetherOperationResult(state, 7L) {
                calls.incrementAndGet()
                response.await()
            }
        }
        withTimeout(1_000) { while (calls.get() == 0) yield() }
        state.value = state.value.copy(followLocked = false)
        response.complete(TogetherRepository.OperationResult(true, "完成"))
        withTimeout(1_000) { publish.await() }

        assertEquals(1, calls.get())
        assertEquals("完成", state.value.toast)
    }

    @Test
    fun operationResultIsDroppedAfterTheAccountChanges() = runBlocking {
        val state = MutableStateFlow(TogetherViewModel.UiState(myUid = 7L))
        val response = CompletableDeferred<TogetherRepository.OperationResult>()
        val started = CompletableDeferred<Unit>()

        val publish = async {
            publishTogetherOperationResult(state, 7L) {
                started.complete(Unit)
                response.await()
            }
        }
        withTimeout(1_000) { started.await() }
        state.value = state.value.copy(myUid = 8L)
        response.complete(TogetherRepository.OperationResult(true, "旧账号结果"))
        withTimeout(1_000) { publish.await() }

        assertEquals(8L, state.value.myUid)
        assertEquals(null, state.value.toast)
    }

    @Test
    fun staleOperationResultIsDroppedWhenTheOriginalAccountReturns() = runBlocking {
        val state = MutableStateFlow(TogetherViewModel.UiState(myUid = 7L))

        publishTogetherOperationResult(state, 7L) {
            state.value = state.value.copy(myUid = 8L)
            state.value = state.value.copy(myUid = 7L)
            TogetherRepository.OperationResult(false, "过期结果", isStale = true)
        }

        assertEquals(7L, state.value.myUid)
        assertEquals(null, state.value.toast)
    }

    @Test
    fun emptyRoom_showsWaitingState_withoutMemberMessage() {
        val presentation = togetherRoomPresentation(TogetherRepository.RoomState())

        assertEquals(0, presentation.memberCount)
        assertEquals("等待连接官方一起听房间", presentation.statusText)
        assertFalse(presentation.canMessageMember)
    }

    @Test
    fun oneMemberRoom_showsRoomState_withoutInventingSync() {
        val presentation = togetherRoomPresentation(
            TogetherRepository.RoomState(
                code = "room-1",
                inRoom = true,
                members = listOf(TogetherRepository.RoomMember(7L, "成员")),
            ),
        )

        assertEquals(1, presentation.memberCount)
        assertEquals("只有 1 位成员在线", presentation.statusText)
        assertTrue(presentation.canMessageMember)
        assertFalse(presentation.showsPlaybackSync)
    }

    @Test
    fun multipleMemberRoom_showsMembers_andKeepsChatAsPrivateMessage() {
        val presentation = togetherRoomPresentation(
            TogetherRepository.RoomState(
                code = "room-2",
                inRoom = true,
                members = listOf(
                    TogetherRepository.RoomMember(7L, "成员一"),
                    TogetherRepository.RoomMember(8L, "成员二"),
                ),
            ),
        )

        assertEquals(2, presentation.memberCount)
        assertEquals("2 位成员在线", presentation.statusText)
        assertTrue(presentation.canMessageMember)
        assertEquals("成员私信", presentation.memberMessageLabel)
        assertFalse(presentation.showsPlaybackSync)
    }
}
