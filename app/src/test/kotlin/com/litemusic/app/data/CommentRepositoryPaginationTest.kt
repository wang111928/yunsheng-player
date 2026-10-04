package com.litemusic.app.data

import com.litemusic.shared.model.Comment
import com.litemusic.shared.model.CommentResponse
import com.litemusic.shared.util.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentRepositoryPaginationTest {
    @Test fun totalKeepsPagingWhenServerOmitsMoreFlag() {
        val page = songCommentPageFrom(
            CommentResponse(code = 200, comments = (1L..20L).map { Comment(commentId = it) }, total = 44),
            offset = 0,
            hot = emptyList(),
        ) as AppResult.Success
        assertTrue(page.data.hasMore)

        val last = songCommentPageFrom(
            CommentResponse(code = 200, comments = (41L..44L).map { Comment(commentId = it) }, total = 44),
            offset = 40,
            hot = emptyList(),
        ) as AppResult.Success
        assertFalse(last.data.hasMore)
    }

    @Test fun businessFailureDoesNotLookLikeEmptyComments() {
        val result = songCommentPageFrom(CommentResponse(code = 405), 0, emptyList())
        assertEquals(405, (result as AppResult.Failure).code)
    }
}
