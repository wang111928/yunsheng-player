package com.litemusic.app.feature.comment

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.litemusic.app.BuildConfig

data class CommentTarget(val threadId: String) {
    companion object {
        fun song(songId: Long) = thread("R_SO_4_$songId")
        fun thread(threadId: String) = CommentTarget(threadId)
    }
}

/** 评论底栏全局控制器：播放页点击评论图标即打开 */
object CommentSheetController {
    val currentTarget = androidx.compose.runtime.mutableStateOf<CommentTarget?>(null)

    fun open(songId: Long, threadId: String? = null) {
        currentTarget.value = threadId?.takeIf { it.isNotBlank() }?.let(CommentTarget::thread)
            ?: CommentTarget.song(songId)
    }

    fun close() {
        currentTarget.value = null
    }
}

@Composable
fun CommentSheet() {
    if (!BuildConfig.FEATURE_COMMENT) return
    val target by CommentSheetController.currentTarget
    if (target == null) return
    CommentSheetContent(target = target!!, onClose = { CommentSheetController.close() })
}
