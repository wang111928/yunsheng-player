package com.litemusic.app.data

import com.litemusic.shared.api.NMApi
import com.litemusic.shared.model.MsgItem
import com.litemusic.shared.model.MsgSession
import com.litemusic.shared.util.AppResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class MsgSessionPage(
    val sessions: List<MsgSession>,
    val more: Boolean,
    val nextOffset: Int,
)

data class MsgHistoryPage(
    val messages: List<MsgItem>,
    val hasMore: Boolean,
)

internal fun displayPrivateMessage(raw: String): String = runCatching {
    val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(raw).jsonObject
    obj["msg"]?.jsonPrimitive?.content ?: raw
}.getOrDefault(raw)

private suspend fun <T> guardedMessageRequest(block: suspend () -> AppResult<T>): AppResult<T> = try {
    block()
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    AppResult.Failure(-1, error.message ?: "私信请求失败", error)
}

interface MsgDataSource {
    suspend fun sessions(offset: Int = 0): AppResult<MsgSessionPage>
    suspend fun history(userId: Long, before: Long = 0): AppResult<MsgHistoryPage>
    suspend fun send(userIds: List<Long>, text: String): AppResult<com.litemusic.shared.model.StatusResponse>
}

/** 私信（Full 版）：会话列表 / 历史 / 发送（发送端点需抓包校准） */
class MsgRepository(private val api: NMApi) : MsgDataSource {

    override suspend fun sessions(offset: Int): AppResult<MsgSessionPage> = guardedMessageRequest {
        when (val result = api.privateMsgs(offset)) {
        is AppResult.Failure -> result
        is AppResult.Success -> if (result.data.code == 200) AppResult.Success(MsgSessionPage(
            sessions = result.data.msgs.map { it.copy(lastMsg = displayPrivateMessage(it.lastMsg)) },
            more = result.data.hasMore,
            nextOffset = offset + result.data.msgs.size,
        ))
        else AppResult.Failure(
            result.data.code,
            result.data.message.ifBlank { "私信会话读取失败(${result.data.code})" },
        )
        }
    }

    override suspend fun history(userId: Long, before: Long): AppResult<MsgHistoryPage> = guardedMessageRequest {
        when (val result = api.privateHistory(userId, time = before)) {
        is AppResult.Failure -> result
        is AppResult.Success -> if (result.data.code == 200) AppResult.Success(MsgHistoryPage(
            messages = result.data.msgs.map { it.copy(msg = displayPrivateMessage(it.msg)) },
            hasMore = result.data.hasMore,
        ))
        else AppResult.Failure(
            result.data.code,
            result.data.message.ifBlank { "私信历史读取失败(${result.data.code})" },
        )
        }
    }

    override suspend fun send(userIds: List<Long>, text: String) = guardedMessageRequest {
        when {
            userIds.isEmpty() -> AppResult.Failure(400, "未选择收件人")
            text.isBlank() -> AppResult.Failure(400, "消息不能为空")
            else -> api.sendPrivateMsg(userIds, text)
        }
    }
}
