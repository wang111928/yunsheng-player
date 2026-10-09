package com.litemusic.app.feature.update

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateApkDownloadTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun returnsOnlyACompleteValidatedApk() = runTest {
        val directory = temporary.newFolder()
        val call = TestCall(body("apk"))
        var validated = false
        val result = async {
            call.downloadVerifiedUpdate(directory, 238, 3, null, { _, _ -> }) {
                assertEquals("apk", it.readText())
                validated = true
            }
        }
        runCurrent()
        call.respond()
        val apk = result.await()
        assertTrue(validated)
        assertEquals(listOf(apk), directory.listFiles()!!.toList())
        assertTrue(apk.name.endsWith(".apk"))
    }

    @Test fun failedValidationDeletesBothPartialAndApk() = runTest {
        supervisorScope {
            val directory = temporary.newFolder()
            val call = TestCall(body("apk"))
            val result = async {
                call.downloadVerifiedUpdate(directory, 238, 3, null, { _, _ -> }) {
                    error("wrong signing key")
                }
            }
            runCurrent()
            call.respond()
            assertEquals("wrong signing key", runCatching { result.await() }.exceptionOrNull()?.message)
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun cancellationDuringBodyReadRemovesItsFilesAndPreservesOtherDownloads() = runTest {
        val directory = temporary.newFolder()
        val existing = File(directory, "other.apk").apply { writeText("keep") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val call = TestCall(body("apk"))
        val result = async {
            call.downloadVerifiedUpdate(directory, 238, 3, null, { _, _ ->
                entered.countDown()
                release.await()
            }) { }
        }
        runCurrent()
        val thread = Thread { call.respond() }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            result.cancelAndJoin()
            assertTrue(call.isCanceled())
        } finally {
            release.countDown()
            thread.join(5_000)
        }
        assertFalse(thread.isAlive)
        assertEquals(listOf(existing), directory.listFiles()!!.toList())
        assertEquals("keep", existing.readText())
    }

    @Test fun cancellationAtResultHandoffDeletesTheValidatedApk() = runTest {
        val directory = temporary.newFolder()
        val call = TestCall(body("apk"))
        val result = async { call.downloadVerifiedUpdate(directory, 238, 3, null, { _, _ -> }) { } }
        runCurrent()
        call.respond()
        assertEquals(1, directory.listFiles()!!.size)
        result.cancelAndJoin()
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun incompleteBodyNeverReachesArchiveValidation() = runTest {
        supervisorScope {
            val directory = temporary.newFolder()
            val call = TestCall(body("apk"))
            var validated = false
            val result = async {
                call.downloadVerifiedUpdate(directory, 238, 5, null, { _, _ -> }) { validated = true }
            }
            runCurrent()
            call.respond()
            assertTrue(runCatching { result.await() }.isFailure)
            assertFalse(validated)
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    private fun body(text: String): ResponseBody = object : ResponseBody() {
        private val buffer = Buffer().writeUtf8(text)
        override fun contentLength(): Long = text.length.toLong()
        override fun contentType() = null
        override fun source(): BufferedSource = buffer
    }

    private class TestCall(body: ResponseBody) : Call {
        private val request = Request.Builder().url("https://github.com/example/update.apk").build()
        private val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(body).build()
        private lateinit var callback: Callback
        private val canceled = AtomicBoolean(false)
        override fun request() = request
        override fun execute(): Response = error("enqueue required")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() { canceled.set(true) }
        override fun isExecuted() = ::callback.isInitialized
        override fun isCanceled() = canceled.get()
        override fun timeout() = okio.Timeout.NONE
        override fun clone(): Call = error("not used")
        fun respond() = callback.onResponse(this, response)
    }
}
