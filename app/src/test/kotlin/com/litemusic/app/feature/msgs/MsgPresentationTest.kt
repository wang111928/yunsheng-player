package com.litemusic.app.feature.msgs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.litemusic.shared.model.MsgItem
import com.litemusic.shared.model.NeteaseUser

class MsgPresentationTest {
    @Test
    fun failedSessionPageStopsAutomaticRequestsUntilAnExplicitRetry() {
        val ready = MsgViewModel.UiState(loading = false, sessionsMore = true)
        assertTrue(canAutoLoadMsgSessions(ready))
        assertFalse(canAutoLoadMsgSessions(ready.copy(error = "网络错误")))
        assertFalse(canAutoLoadMsgSessions(ready.copy(refreshing = true)))
        assertFalse(canAutoLoadMsgSessions(ready.copy(loading = true)))
        assertFalse(canAutoLoadMsgSessions(ready.copy(loadingMoreSessions = true)))
    }

    @Test
    fun prependingHistoryDoesNotScrollToTheBottomButNewMessagesCan() {
        assertFalse(shouldAutoScrollChat("server:9", "server:9", nearBottom = true))
        assertTrue(shouldAutoScrollChat("server:9", "server:10", nearBottom = true))
        assertTrue(shouldAutoScrollChat(null, "server:9", nearBottom = true))
        assertFalse(shouldAutoScrollChat("server:9", "server:10", nearBottom = false))
        assertFalse(shouldAutoScrollChat("server:9", null, nearBottom = true))
    }

    @Test
    fun chatEmojiPanel_exposesEveryTokenThatTheMessageRendererSupports() {
        assertEquals(
            listOf(
                "[认可]", "[感动]", "[偷笑]", "[大笑]", "[爱心]", "[偷窥]", "[装可爱]",
                "[可爱]", "[流泪]", "[生气]", "[呲牙]", "[亲亲]", "[惊恐]", "[酷]",
            ),
            chatEmojiTokens,
        )
    }

    @Test
    fun insertingAnEmojiTokenKeepsExistingDraftText() {
        assertEquals("你好[偷笑]", insertChatToken("你好", "[偷笑]"))
        assertEquals("[认可]", insertChatToken("", "[认可]"))
    }

    @Test
    fun chatRequestTracker_rejectsAnEarlierVisitWhenReturningToTheSameUser() {
        val tracker = ChatRequestTracker()
        val firstVisit = tracker.open(42L)
        tracker.open(7L)
        val returnedVisit = tracker.open(42L)

        assertFalse(tracker.isCurrent(firstVisit))
        assertTrue(tracker.isCurrent(returnedVisit))
    }

    @Test
    fun messageFromChatPartner_isNotMine() {
        assertTrue(messageIsFromOther(senderId = 42L, chatUserId = 42L))
        assertFalse(messageIsFromOther(senderId = 7L, chatUserId = 42L))
    }

    @Test
    fun outgoingMessageAvatarUsesTheCurrentAccountInsteadOfTheRecipient() {
        val mine = NeteaseUser(userId = 7L, nickname = "我")
        val recipient = NeteaseUser(userId = 42L, nickname = "对方")

        val user = chatAvatarUser(
            MsgItem(fromUser = mine, toUser = recipient, msg = "你好"),
            fromOther = false,
        )

        assertEquals(7L, user?.userId)
    }

    @Test
    fun mergeChatMessages_keepsSentMessageAndRemovesHistoryDuplicate() {
        val sent = MsgItem(
            msgId = -1L,
            toUser = NeteaseUser(userId = 42L),
            msg = "刚刚发出的消息",
            time = 1_000L,
        )
        val historyCopy = MsgItem(
            msgId = 901L,
            fromUser = NeteaseUser(userId = 7L),
            toUser = NeteaseUser(userId = 42L),
            msg = sent.msg,
            time = sent.time,
        )
        val older = MsgItem(
            msgId = 900L,
            fromUser = NeteaseUser(userId = 42L),
            toUser = NeteaseUser(userId = 7L),
            msg = "更早的消息",
            time = 500L,
        )

        val merged = mergeChatMessages(
            history = listOf(historyCopy, older),
            pending = listOf(sent),
        )

        assertEquals(listOf(older.msgId, historyCopy.msgId), merged.map { it.msgId })
        assertEquals(1, merged.count { it.msg == sent.msg })
    }

    @Test
    fun mergeChatMessages_doesNotReplaceAnOutgoingPendingWithAnIncomingMessage() {
        val pending = MsgItem(
            msgId = -1L,
            toUser = NeteaseUser(userId = 42L),
            msg = "同一句话",
            time = 1_000L,
        )
        val incoming = MsgItem(
            msgId = 901L,
            fromUser = NeteaseUser(userId = 42L),
            toUser = NeteaseUser(userId = 7L),
            msg = pending.msg,
            time = pending.time,
        )

        val merged = mergeChatMessages(history = listOf(incoming), pending = listOf(pending))

        assertEquals(listOf(pending.msgId, incoming.msgId), merged.map { it.msgId })
    }

    @Test
    fun mergeChatMessages_matchesOnlyOnePendingToEachServerEcho() {
        val firstPending = MsgItem(
            msgId = -1L,
            toUser = NeteaseUser(userId = 42L),
            msg = "重复发送",
            time = 1_000L,
        )
        val secondPending = firstPending.copy(msgId = -2L)
        val serverEcho = MsgItem(
            msgId = 901L,
            fromUser = NeteaseUser(userId = 7L),
            toUser = NeteaseUser(userId = 42L),
            msg = firstPending.msg,
            time = firstPending.time,
        )

        val merged = mergeChatMessages(
            history = listOf(serverEcho),
            pending = listOf(firstPending, secondPending),
        )

        assertEquals(listOf(secondPending.msgId, serverEcho.msgId), merged.map { it.msgId })
    }

    @Test
    fun mergeChatMessages_keepsTwoDifferentServerIdsWithTheSameText() {
        val first = MsgItem(msgId = 901L, msg = "重复", time = 1_000L)
        val second = first.copy(msgId = 902L)

        val merged = mergeChatMessages(history = listOf(first, second), pending = emptyList())

        assertEquals(listOf(901L, 902L), merged.map { it.msgId })
    }

    @Test
    fun mergeChatMessages_keepsHistoryThatHasNoServerId() {
        val idlessHistory = MsgItem(msgId = 0L, msg = "没有消息 ID", time = 1_000L)

        val merged = mergeChatMessages(history = listOf(idlessHistory), pending = emptyList())

        assertEquals(listOf(idlessHistory), merged)
    }

    @Test
    fun mergeChatMessages_replacesPendingWithAnIdlessOutgoingServerEcho() {
        val pending = MsgItem(
            msgId = -1L,
            toUser = NeteaseUser(userId = 42L),
            msg = "刚发出的无 ID 消息",
            time = 1_000L,
        )
        val idlessEcho = MsgItem(
            msgId = 0L,
            fromUser = NeteaseUser(userId = 7L),
            toUser = NeteaseUser(userId = 42L),
            msg = pending.msg,
            time = 1_001L,
        )

        val reconciliation = reconcileChatMessages(listOf(idlessEcho), listOf(pending))

        assertEquals(listOf(0L), reconciliation.messages.map { it.msgId })
        assertTrue(reconciliation.pending.isEmpty())
    }

    @Test
    fun idlessHistoryVisibleBeforeSendCannotConfirmANewPendingMessage() {
        val oldServerMessage = MsgItem(
            msgId = 0L,
            fromUser = NeteaseUser(userId = 7L),
            toUser = NeteaseUser(userId = 42L),
            msg = "同一句话",
            time = 1_000L,
        )
        val pending = MsgItem(
            msgId = -1L,
            toUser = NeteaseUser(userId = 42L),
            msg = oldServerMessage.msg,
            time = 1_500L,
        )

        val reconciliation = reconcileChatMessages(
            history = listOf(oldServerMessage),
            pending = listOf(pending),
            blockedServerRowsByPending = mapOf(
                pending.msgId to setOf("row:7:42:1000:0:同一句话"),
            ),
        )

        assertEquals(listOf(oldServerMessage.msgId, pending.msgId), reconciliation.messages.map { it.msgId })
        assertEquals(listOf(pending), reconciliation.pending)
    }

    @Test
    fun mergeChatMessages_doesNotTreatPartnerAsTheOutgoingSender() {
        val pending = MsgItem(
            msgId = -1L,
            toUser = NeteaseUser(userId = 42L),
            msg = "同一句话",
            time = 1_000L,
        )
        val partnerMessage = MsgItem(
            msgId = 901L,
            fromUser = NeteaseUser(userId = 42L),
            toUser = NeteaseUser(userId = 42L),
            msg = pending.msg,
            time = pending.time,
        )

        val merged = mergeChatMessages(history = listOf(partnerMessage), pending = listOf(pending))

        assertEquals(listOf(pending.msgId, partnerMessage.msgId), merged.map { it.msgId })
    }

    @Test
    fun mergeChatMessages_doesNotConfirmPendingWhenTheServerSenderIsMissing() {
        val pending = MsgItem(
            msgId = -1L,
            toUser = NeteaseUser(userId = 42L),
            msg = "同一句话",
            time = 1_000L,
        )
        val senderlessServerMessage = MsgItem(
            msgId = 901L,
            toUser = NeteaseUser(userId = 42L),
            msg = pending.msg,
            time = pending.time,
        )

        val merged = mergeChatMessages(history = listOf(senderlessServerMessage), pending = listOf(pending))

        assertEquals(listOf(pending.msgId, senderlessServerMessage.msgId), merged.map { it.msgId })
    }

    @Test
    fun reconcileChatMessages_doesNotUseHistoryAlreadyVisibleWhenConfirmingANewSend() {
        val oldServerMessage = MsgItem(
            msgId = 901L,
            fromUser = NeteaseUser(userId = 7L),
            toUser = NeteaseUser(userId = 42L),
            msg = "同一句话",
            time = 1_000L,
        )
        val newPending = MsgItem(
            msgId = -1L,
            toUser = NeteaseUser(userId = 42L),
            msg = oldServerMessage.msg,
            time = 1_500L,
        )

        val reconciliation = reconcileChatMessages(
            history = listOf(oldServerMessage),
            pending = listOf(newPending),
            blockedServerIdsByPending = mapOf(newPending.msgId to setOf(oldServerMessage.msgId)),
        )

        assertEquals(listOf(oldServerMessage.msgId, newPending.msgId), reconciliation.messages.map { it.msgId })
        assertEquals(listOf(newPending), reconciliation.pending)
    }

    @Test
    fun reduceChatSendSuccess_keepsANewPendingWhenItsMatchingServerRowWasAlreadyVisible() {
        val oldServerMessage = MsgItem(
            msgId = 901L,
            fromUser = NeteaseUser(userId = 7L),
            toUser = NeteaseUser(userId = 42L),
            msg = "同一句话",
            time = 1_000L,
        )
        val newPending = MsgItem(
            msgId = -1L,
            toUser = NeteaseUser(userId = 42L),
            msg = oldServerMessage.msg,
            time = 1_500L,
        )

        val update = reduceChatSendSuccess(
            currentMessages = listOf(oldServerMessage),
            pending = listOf(newPending),
            requestIsCurrent = true,
            currentInput = "同一句话",
            inputIsUnchanged = true,
            blockedServerIdsByPending = mapOf(newPending.msgId to setOf(oldServerMessage.msgId)),
        )

        assertEquals(listOf(oldServerMessage.msgId, newPending.msgId), update.messages?.map { it.msgId })
    }

    @Test
    fun reduceChatSendSuccess_keepsVisibleHistoryWhileAddingPendingMessage() {
        val history = MsgItem(msgId = 901L, msg = "已有历史", time = 1_000L)
        val pending = MsgItem(msgId = -1L, toUser = NeteaseUser(userId = 42L), msg = "刚发送", time = 2_000L)

        val update = reduceChatSendSuccess(
            currentMessages = listOf(history),
            pending = listOf(pending),
            requestIsCurrent = true,
            currentInput = "刚发送",
            inputIsUnchanged = true,
        )

        assertEquals(listOf(history.msgId, pending.msgId), update.messages?.map { it.msgId })
        assertEquals("", update.input)
    }

    @Test
    fun reduceChatSendSuccess_preservesAnotherChatAndNewerInput() {
        val history = MsgItem(msgId = 901L, msg = "已有历史", time = 1_000L)
        val pending = MsgItem(msgId = -1L, toUser = NeteaseUser(userId = 42L), msg = "刚发送", time = 2_000L)

        val replacedChat = reduceChatSendSuccess(
            currentMessages = listOf(history),
            pending = listOf(pending),
            requestIsCurrent = false,
            currentInput = "另一个会话输入",
            inputIsUnchanged = false,
        )
        val editedInput = reduceChatSendSuccess(
            currentMessages = listOf(history),
            pending = listOf(pending),
            requestIsCurrent = true,
            currentInput = "发送期间的新输入",
            inputIsUnchanged = false,
        )

        assertEquals(null, replacedChat.messages)
        assertEquals(null, replacedChat.input)
        assertEquals("发送期间的新输入", editedInput.input)
    }

    @Test
    fun reduceChatOpen_clearsAnotherChatsDraftButKeepsASameChatsHistoryAndDraft() {
        val history = MsgItem(msgId = 901L, msg = "已有历史", time = 1_000L)

        val switched = reduceChatOpen(
            currentUserId = 7L,
            userId = 42L,
            currentMessages = listOf(history),
            pending = emptyList(),
            currentInput = "用户 7 的草稿",
        )
        val refreshed = reduceChatOpen(
            currentUserId = 42L,
            userId = 42L,
            currentMessages = listOf(history),
            pending = emptyList(),
            currentInput = "用户 42 的草稿",
        )

        assertEquals("", switched.input)
        assertEquals(emptyList<MsgItem>(), switched.messages)
        assertEquals("用户 42 的草稿", refreshed.input)
        assertEquals(listOf(history), refreshed.messages)
    }

    @Test
    fun chatMessageKeys_distinguishUnknownMessagesWithTheSameTimeAndText() {
        val first = MsgItem(msgId = 0L, msg = "无 ID", time = 1_000L)
        val second = first.copy()

        val keys = chatMessageKeys(listOf(first, second))

        assertEquals(2, keys.distinct().size)
        assertTrue(keys.all { it.startsWith("unknown:") })
    }

    @Test
    fun chatListItems_bindEachUniqueKeyToItsMessage() {
        val first = MsgItem(msgId = 0L, msg = "重复的无 ID 消息", time = 1_000L)
        val second = first.copy()

        val rows = chatListItems(listOf(first, second))

        assertEquals(listOf(first, second), rows.map { it.item })
        assertEquals(2, rows.map { it.key }.distinct().size)
    }

    @Test
    fun idlessHistoryStillProvidesATimeCursorForLoadingOlderMessages() {
        val rows = listOf(
            MsgItem(msgId = 0L, msg = "较新", time = 2_000L),
            MsgItem(msgId = 0L, msg = "较早", time = 1_000L),
            MsgItem(msgId = -1L, msg = "本地待发送", time = 500L),
        )

        assertEquals(1_000L, olderHistoryCursor(rows))
    }

    @Test
    fun idlessSessionsUseParticipantsInsteadOfTheRepeatedZeroId() {
        val sessions = listOf(
            com.litemusic.shared.model.MsgSession(
                fromUser = NeteaseUser(userId = 1L),
                toUser = NeteaseUser(userId = 2L),
            ),
            com.litemusic.shared.model.MsgSession(
                fromUser = NeteaseUser(userId = 1L),
                toUser = NeteaseUser(userId = 3L),
            ),
        )

        assertEquals(2, sessionListKeys(sessions).distinct().size)
        assertEquals(2, mergeSessions(emptyList(), sessions).size)
    }

    @Test
    fun sessionListItems_bindEachUniqueKeyToItsSession() {
        val first = com.litemusic.shared.model.MsgSession(
            fromUser = NeteaseUser(userId = 1L),
            toUser = NeteaseUser(userId = 2L),
        )
        val second = first.copy()

        val rows = sessionListItems(listOf(first, second))

        assertEquals(listOf(first, second), rows.map { it.item })
        assertEquals(2, rows.map { it.key }.distinct().size)
    }

    @Test
    fun idlessRowsFromOverlappingHistoryPagesAreNotDuplicated() {
        val repeated = MsgItem(
            msgId = 0L,
            fromUser = NeteaseUser(userId = 7L),
            toUser = NeteaseUser(userId = 8L),
            msg = "重叠行",
            time = 1_000L,
        )

        assertEquals(listOf(repeated), mergeServerHistoryPages(listOf(repeated), listOf(repeated)))
    }

    @Test
    fun historySyncKeepsDraftPendingAndDeduplicatesOlderServerRows() {
        val pending = MsgItem(msgId = -1L, toUser = NeteaseUser(userId = 42L), msg = "草稿发送", time = 3_000L)
        val current = MsgItem(msgId = 11L, msg = "当前", time = 2_000L)
        val older = MsgItem(msgId = 10L, msg = "更早", time = 1_000L)

        val reconciliation = reconcileChatMessages(
            history = listOf(older, current, current),
            pending = listOf(pending),
        )

        assertEquals(listOf(10L, 11L, -1L), reconciliation.messages.map { it.msgId })
        assertEquals(listOf(pending), reconciliation.pending)
    }

    @Test
    fun requestTrackerRejectsOldAccountVisitAfterANewVisit() {
        val tracker = ChatRequestTracker()
        val accountAVisit = tracker.open(42L)
        tracker.close()
        val accountBVisit = tracker.open(42L)

        assertFalse(tracker.isCurrent(accountAVisit))
        assertTrue(tracker.isCurrent(accountBVisit))
    }
}
