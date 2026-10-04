package com.litemusic.app.feature.msgs

import com.litemusic.app.data.MsgDataSource
import com.litemusic.app.data.MsgHistoryPage
import com.litemusic.app.data.MsgSessionPage
import com.litemusic.data.prefs.AuthStore
import com.litemusic.shared.model.MsgItem
import com.litemusic.shared.model.MsgSession
import com.litemusic.shared.model.StatusResponse
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MsgViewModelRuntimeTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun stopChatSyncRejectsAnOlderHistoryResponse() = runTest(dispatcher) {
        val source = FakeMsgSource()
        val model = MsgViewModel(source)
        model.openChat(7L); advanceUntilIdle()
        source.nextHistory.complete(AppResult.Success(historyPage(MsgItem(msgId = 1, msg = "first", time = 1))))
        advanceUntilIdle()
        source.nextHistory = CompletableDeferred()
        model.syncChat(); advanceUntilIdle()
        model.stopChatSync()
        source.nextHistory.complete(AppResult.Success(historyPage(MsgItem(msgId = 2, msg = "stale", time = 2))))
        advanceUntilIdle()
        assertEquals(listOf(1L), model.state.value.chatMessages.map { it.msgId })
    }

    @Test fun syncKeepsOlderPageAfterPagination() = runTest(dispatcher) {
        val source = FakeMsgSource(); val model = MsgViewModel(source)
        model.openChat(7L); advanceUntilIdle()
        source.nextHistory.complete(AppResult.Success(historyPage(MsgItem(msgId = 2, msg = "new", time = 20), more = true)))
        advanceUntilIdle()
        source.nextHistory = CompletableDeferred(); model.loadOlder(); advanceUntilIdle()
        source.nextHistory.complete(AppResult.Success(historyPage(MsgItem(msgId = 1, msg = "old", time = 10))))
        advanceUntilIdle()
        source.nextHistory = CompletableDeferred(); model.syncChat(); advanceUntilIdle()
        source.nextHistory.complete(AppResult.Success(historyPage(MsgItem(msgId = 2, msg = "new", time = 20), more = true)))
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), model.state.value.chatMessages.map { it.msgId })
    }

    @Test fun syncWhileLoadingOlder_doesNotAbandonTheOlderPageOrLeaveTheButtonLoading() = runTest(dispatcher) {
        val source = FakeMsgSource(); val model = MsgViewModel(source)
        model.openChat(7L); advanceUntilIdle()
        source.nextHistory.complete(AppResult.Success(historyPage(MsgItem(msgId = 2, msg = "new", time = 20), more = true)))
        advanceUntilIdle()

        val older = CompletableDeferred<AppResult<MsgHistoryPage>>()
        val sync = CompletableDeferred<AppResult<MsgHistoryPage>>()
        source.historyQueue += older
        source.historyQueue += sync
        model.loadOlder(); advanceUntilIdle()
        model.syncChat(); advanceUntilIdle()
        sync.complete(AppResult.Success(historyPage(MsgItem(msgId = 2, msg = "new", time = 20), more = true)))
        advanceUntilIdle()
        older.complete(AppResult.Success(historyPage(MsgItem(msgId = 1, msg = "old", time = 10))))
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), model.state.value.chatMessages.map { it.msgId })
        assertFalse(model.state.value.loadingOlder)
    }

    @Test fun stoppingChatSync_releasesAnInFlightOlderPage() = runTest(dispatcher) {
        val source = FakeMsgSource(); val model = MsgViewModel(source)
        model.openChat(7L); advanceUntilIdle()
        source.nextHistory.complete(AppResult.Success(historyPage(MsgItem(msgId = 2, msg = "new", time = 20), more = true)))
        advanceUntilIdle()
        source.nextHistory = CompletableDeferred()
        model.loadOlder(); advanceUntilIdle()

        model.stopChatSync()

        assertFalse(model.state.value.loadingOlder)
    }

    @Test fun sessionPageRequest_isRejectedAfterAccountSwitch() {
        val tracker = SessionRequestTracker()
        val oldAccount = AuthStore.Session(musicU = "old", userId = 1L, loginAt = 10L)
        val newAccount = AuthStore.Session(musicU = "new", userId = 2L, loginAt = 20L)
        val oldRequest = tracker.begin(oldAccount)

        tracker.invalidate()

        assertFalse(tracker.isCurrent(oldRequest, newAccount))
    }

    @Test fun successfulSendReadsBackServerEchoAndReplacesPending() = runTest(dispatcher) {
        val source = FakeMsgSource(); val model = MsgViewModel(source)
        model.openChat(7L); advanceUntilIdle()
        source.nextHistory.complete(AppResult.Success(historyPage())); advanceUntilIdle()
        source.nextHistory = CompletableDeferred()
        model.setInput("hello"); model.send(); advanceUntilIdle()
        assertEquals("", model.state.value.input)
        assertEquals(1, model.state.value.chatMessages.size)
        source.nextHistory.complete(AppResult.Success(historyPage(MsgItem(
            msgId = 9, msg = "hello", time = System.currentTimeMillis(),
            fromUser = com.litemusic.shared.model.NeteaseUser(userId = 42),
            toUser = com.litemusic.shared.model.NeteaseUser(userId = 7),
        )))); advanceUntilIdle()
        assertEquals(listOf(9L), model.state.value.chatMessages.map { it.msgId })
    }

    @Test fun failedSendKeepsInputAndDoesNotAddPending() = runTest(dispatcher) {
        val source = FakeMsgSource(); val model = MsgViewModel(source)
        model.openChat(7L); advanceUntilIdle(); source.nextHistory.complete(AppResult.Success(historyPage())); advanceUntilIdle()
        model.setInput("hello"); source.sendResult = AppResult.Failure(500, "failed"); model.send(); advanceUntilIdle()
        assertEquals("hello", model.state.value.input)
        assertFalse(model.state.value.chatMessages.any { it.msgId < 0 })
    }

    @Test fun pullSyncFailureKeepsVisibleSessionsAndExposesTheError() = runTest(dispatcher) {
        val source = FakeMsgSource().apply {
            sessionResult = AppResult.Success(MsgSessionPage(listOf(MsgSession(id = 7L, lastMsg = "已有会话")), more = false, nextOffset = 1))
        }
        val model = MsgViewModel(source)
        model.loadSessions(); advanceUntilIdle()

        source.sessionResult = AppResult.Failure(500, "同步失败")
        model.loadSessions(refresh = true); advanceUntilIdle()

        assertEquals(listOf(7L), model.state.value.sessions.map { it.id })
        assertEquals("同步失败", model.state.value.error)
        assertFalse(model.state.value.refreshing)
    }

    @Test fun newerSessionRefreshRejectsAnOlderSessionResponse() = runTest(dispatcher) {
        val source = FakeMsgSource()
        val oldResponse = CompletableDeferred<AppResult<MsgSessionPage>>()
        source.nextSessions = oldResponse
        val model = MsgViewModel(source)
        model.loadSessions(); advanceUntilIdle()

        source.nextSessions = null
        source.sessionResult = AppResult.Success(MsgSessionPage(listOf(MsgSession(id = 2L)), more = false, nextOffset = 1))
        model.loadSessions(refresh = true); advanceUntilIdle()
        oldResponse.complete(AppResult.Success(MsgSessionPage(listOf(MsgSession(id = 1L)), more = false, nextOffset = 1)))
        advanceUntilIdle()

        assertEquals(listOf(2L), model.state.value.sessions.map { it.id })
    }

    @Test fun sessionPaginationAppendsTheNextServerPage() = runTest(dispatcher) {
        val source = FakeMsgSource().apply {
            sessionResult = AppResult.Success(MsgSessionPage(listOf(MsgSession(id = 1L)), more = true, nextOffset = 1))
        }
        val model = MsgViewModel(source)
        model.loadSessions(); advanceUntilIdle()

        source.sessionResult = AppResult.Success(MsgSessionPage(listOf(MsgSession(id = 2L)), more = false, nextOffset = 2))
        model.loadMoreSessions(); advanceUntilIdle()

        assertEquals(listOf(1L, 2L), model.state.value.sessions.map { it.id })
        assertFalse(model.state.value.sessionsMore)
    }

    @Test fun repeatedOlderHistoryPageStopsFurtherPaginationEvenWhenServerSaysMore() = runTest(dispatcher) {
        val source = FakeMsgSource(); val model = MsgViewModel(source)
        val row = MsgItem(msgId = 2, msg = "new", time = 20)
        model.openChat(7L); advanceUntilIdle()
        source.nextHistory.complete(AppResult.Success(historyPage(row, more = true))); advanceUntilIdle()

        source.nextHistory = CompletableDeferred()
        model.loadOlder(); advanceUntilIdle()
        source.nextHistory.complete(AppResult.Success(historyPage(row, more = true))); advanceUntilIdle()

        assertFalse(model.state.value.hasOlder)
    }
}

private class FakeMsgSource : MsgDataSource {
    var nextHistory = CompletableDeferred<AppResult<MsgHistoryPage>>()
    var sendResult: AppResult<StatusResponse> = AppResult.Success(StatusResponse(code = 200))
    var sessionResult: AppResult<MsgSessionPage> = AppResult.Success(MsgSessionPage(emptyList(), more = false, nextOffset = 0))
    var nextSessions: CompletableDeferred<AppResult<MsgSessionPage>>? = null
    val historyQueue = ArrayDeque<CompletableDeferred<AppResult<MsgHistoryPage>>>()
    override suspend fun sessions(offset: Int) = nextSessions?.await() ?: sessionResult
    override suspend fun history(userId: Long, before: Long) =
        if (historyQueue.isNotEmpty()) historyQueue.removeFirst().await() else nextHistory.await()
    override suspend fun send(userIds: List<Long>, text: String) = sendResult
}

private fun historyPage(vararg messages: MsgItem, more: Boolean = false) = MsgHistoryPage(
    messages = messages.toList(),
    hasMore = more,
)
