package com.litemusic.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoutWebSessionTest {
    @Test fun localAuthIsClearedOnlyAfterWebCookiesHaveBeenFlushed() = runTest {
        val steps = mutableListOf<String>()
        val cookieCallback = CompletableDeferred<Unit>()
        var localAuthCleared = false

        val job = launch {
            clearLogoutStateInOrder(
                clearWebCookies = {
                    steps += "cookies-requested"
                    cookieCallback.await()
                    steps += "cookies-flushed"
                },
                clearLocalAuth = {
                    localAuthCleared = true
                    steps += "auth"
                },
                clearVip = { steps += "vip" },
            )
        }

        runCurrent()
        assertEquals(listOf("cookies-requested"), steps)
        assertTrue("local auth must wait for the WebView cookie callback", !localAuthCleared)

        cookieCallback.complete(Unit)
        job.join()
        assertEquals(listOf("cookies-requested", "cookies-flushed", "auth", "vip"), steps)
        assertTrue(localAuthCleared)
    }
}
