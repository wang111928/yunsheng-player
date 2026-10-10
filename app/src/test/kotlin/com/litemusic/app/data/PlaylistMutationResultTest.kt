package com.litemusic.app.data

import com.litemusic.shared.util.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistMutationResultTest {
    @Test
    fun successfulTransportWithRejectedBusinessCodeDoesNotInvalidateOrUpdateUi() {
        val result = requirePlaylistMutationSuccess(
            AppResult.Success(PlaylistMutationStatus(code = 502, message = "没有权限")),
            action = "修改歌单",
        ) { it.code to it.message }

        assertTrue(result is AppResult.Failure)
        assertEquals("没有权限", (result as AppResult.Failure).message)
    }

    @Test
    fun acceptedBusinessCodeKeepsTheResponse() {
        val response = PlaylistMutationStatus(code = 200)
        val result = requirePlaylistMutationSuccess(AppResult.Success(response), "删歌") { it.code to it.message }

        assertEquals(response, (result as AppResult.Success).data)
    }
}

private data class PlaylistMutationStatus(val code: Int, val message: String = "")
