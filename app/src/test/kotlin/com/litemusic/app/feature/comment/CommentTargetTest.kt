package com.litemusic.app.feature.comment

import org.junit.Assert.assertEquals
import org.junit.Test

class CommentTargetTest {
    @Test
    fun songTargetUsesSongCommentThread() {
        assertEquals("R_SO_4_42", CommentTarget.song(42L).threadId)
    }

    @Test
    fun programTargetKeepsTheProgramCommentThread() {
        assertEquals("A_DJ_1_7", CommentTarget.thread("A_DJ_1_7").threadId)
    }
}
