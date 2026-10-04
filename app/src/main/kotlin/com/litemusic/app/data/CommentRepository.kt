package com.litemusic.app.data

import com.litemusic.shared.api.NMApi
import com.litemusic.shared.model.Comment
import com.litemusic.shared.model.CommentResponse
import com.litemusic.shared.util.AppResult
import kotlinx.coroutines.flow.first

internal fun songCommentPageFrom(
    response: CommentResponse,
    offset: Int,
    hot: List<Comment>,
): AppResult<CommentRepository.CommentBundle> = if (response.code != 200) {
    AppResult.Failure(response.code, "评论读取失败(${response.code})")
} else AppResult.Success(
    CommentRepository.CommentBundle(
        hot = hot,
        newest = response.comments,
        total = response.total,
        hasMore = response.comments.isNotEmpty() &&
            (response.hasMore || offset + response.comments.size < response.total),
    ),
)

/**
 * 评论：列表 / 发表 / 点赞 / 楼中楼 + 质量过滤（反诈标记 / 关键词屏蔽 / 低质折叠）。
 */
class CommentRepository(
    private val api: NMApi,
    private val settings: com.litemusic.data.prefs.SettingsStore,
    private val blockedDao: com.litemusic.data.db.BlockedKeywordDao,
) {
    data class CommentBundle(
        val hot: List<Comment> = emptyList(),
        val newest: List<Comment> = emptyList(),
        val total: Long = 0,
        val hasMore: Boolean = false,
    )

    /** PiliPlus 反诈标记关键词 */
    private val scamRegex = Regex("加(微信|威|v|V)|二维码|返利|刷单|兼职|贷款|裸聊|赌博|博彩")

    fun isScam(comment: Comment): Boolean = scamRegex.containsMatchIn(comment.content)

    suspend fun loadThread(threadId: String, offset: Int = 0): AppResult<CommentBundle> {
        val newest = api.getCommentsForThread(threadId, offset)
        val hot = if (offset == 0) api.getHotCommentsForThread(threadId) else null
        return when (newest) {
            is AppResult.Success -> songCommentPageFrom(
                newest.data,
                offset,
                hot?.let { if (it is AppResult.Success && it.data.code == 200) it.data.hotComments else emptyList() } ?: emptyList(),
            )
            is AppResult.Failure -> newest
        }
    }

    suspend fun postThread(threadId: String, content: String) = api.postEventComment(threadId, content)

    suspend fun replyThread(threadId: String, commentId: Long, content: String) =
        api.replyEventComment(threadId, commentId, content)

    suspend fun likeThread(threadId: String, commentId: Long, like: Boolean) =
        api.likeEventComment(threadId, commentId, like)

    /** 是否命中屏蔽关键词（设置中的正则列表） */
    suspend fun isBlocked(content: String): Boolean {
        val regexes = settings.blockedRegex.first()
        return regexes.any { runCatching { Regex(it).containsMatchIn(content) }.getOrDefault(false) }
    }

    /** 低质评论：纯表情 / 无意义短评论 */
    fun isLowQuality(comment: Comment): Boolean {
        val c = comment.content.trim()
        if (c.length <= 2) return true
        val emojiOnly = c.replace(Regex("[\\p{So}\\p{Sk}\\p{Sc}]"), "").isBlank()
        return emojiOnly
    }
}
