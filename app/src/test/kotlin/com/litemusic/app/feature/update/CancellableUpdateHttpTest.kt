package com.litemusic.app.feature.update

import java.io.IOException
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
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CancellableUpdateHttpTest {
    @Test
    fun consumesAndClosesASuccessfulResponse() = runTest {
        val body = CloseTrackingBody("release")
        val call = ControlledCall(response(body))
        val result = async { call.consumeCancellable { response, ensureActive ->
            ensureActive()
            response.body!!.string()
        } }
        runCurrent()

        call.respond()

        assertEquals("release", result.await())
        assertTrue(body.closed.get())
        assertFalse(call.isCanceled())
    }

    @Test
    fun propagatesBodyConsumerFailuresAndClosesTheResponse() = runTest {
        supervisorScope {
        val body = CloseTrackingBody("release")
        val call = ControlledCall(response(body))
        val result = async { call.consumeCancellable<String> { _, _ -> error("invalid archive") } }
        runCurrent()

        call.respond()

        val failure = runCatching { result.await() }.exceptionOrNull()
        assertEquals("invalid archive", failure?.message)
        assertTrue(body.closed.get())
        }
    }

    @Test
    fun cancellationCancelsCallWhileTheResponseBodyConsumerIsBlockedThenClosesResponse() = runTest {
        val body = CloseTrackingBody("release")
        val call = ControlledCall(response(body))
        val enteredConsumer = CountDownLatch(1)
        val releaseConsumer = CountDownLatch(1)
        val result = async {
            call.consumeCancellable { _, ensureActive ->
                enteredConsumer.countDown()
                releaseConsumer.await()
                ensureActive()
                "release"
            }
        }
        runCurrent()
        val callbackThread = Thread { call.respond() }.apply { start() }
        assertTrue(enteredConsumer.await(5, TimeUnit.SECONDS))

        result.cancelAndJoin()

        assertTrue(call.isCanceled())
        releaseConsumer.countDown()
        callbackThread.join(5_000)
        assertTrue(body.closed.get())
    }

    @Test
    fun cancellationDuringResultHandoffDiscardsTheProducedValue() = runTest {
        val body = CloseTrackingBody("release")
        val call = ControlledCall(response(body))
        val discarded = AtomicBoolean(false)
        val result = async {
            call.consumeCancellable(onDiscard = { discarded.set(it == "release") }) { _, _ -> "release" }
        }
        runCurrent()

        call.respond()
        result.cancelAndJoin()

        assertTrue(discarded.get())
        assertTrue(body.closed.get())
    }

    @Test
    fun propagatesOkHttpFailure() = runTest {
        supervisorScope {
        val call = ControlledCall(response(CloseTrackingBody("unused")))
        val result = async { call.consumeCancellable<String> { _, _ -> "unused" } }
        runCurrent()

        call.fail(IOException("connection lost"))

        assertEquals("connection lost", runCatching { result.await() }.exceptionOrNull()?.message)
        }
    }

    private fun response(body: ResponseBody): Response = Response.Builder()
        .request(Request.Builder().url("https://updates.example/release.apk").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body)
        .build()

    private class ControlledCall(private val response: Response) : Call {
        private lateinit var callback: Callback
        private var executed = false
        private val cancelled = AtomicBoolean(false)

        override fun request(): Request = response.request
        override fun execute(): Response = error("asynchronous test call")
        override fun enqueue(responseCallback: Callback) {
            check(!executed)
            executed = true
            callback = responseCallback
        }
        override fun cancel() { cancelled.set(true) }
        override fun isExecuted(): Boolean = executed
        override fun isCanceled(): Boolean = cancelled.get()
        override fun timeout() = okio.Timeout.NONE
        override fun clone(): Call = error("not needed by this test")

        fun respond() = callback.onResponse(this, response)
        fun fail(error: IOException) = callback.onFailure(this, error)
    }

    private class CloseTrackingBody(content: String) : ResponseBody() {
        val closed = AtomicBoolean(false)
        private val source: BufferedSource = object : ForwardingSource(Buffer().writeUtf8(content)) {
            override fun close() {
                closed.set(true)
                super.close()
            }
        }.buffer()

        override fun contentLength(): Long = -1L
        override fun contentType() = null
        override fun source(): BufferedSource = source
    }
}
