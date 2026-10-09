package com.litemusic.app.feature.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/**
 * Consumes an OkHttp response without losing coroutine cancellation after headers arrive.
 *
 * OkHttp invokes [block] on its callback thread. The cancellation handler stays registered for
 * the entire body read, so cancelling the caller also cancels a blocked socket read. Results
 * produced at the cancellation handoff are returned to [onDiscard] for their owner to release.
 */
suspend fun <T> Call.consumeCancellable(
    onDiscard: (T) -> Unit = {},
    block: (Response, ensureActive: () -> Unit) -> T,
): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
        }

        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) {
                response.close()
                return
            }
            try {
                val result = response.use {
                    block(it) { continuation.context.ensureActive() }
                }
                continuation.resume(result, onCancellation = { _, discarded, _ -> onDiscard(discarded) })
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    call.cancel()
                    continuation.cancel(error)
                } else if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(error))
                }
            }
        }
    })
}
