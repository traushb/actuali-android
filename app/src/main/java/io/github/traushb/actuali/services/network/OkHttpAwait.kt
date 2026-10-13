package io.github.traushb.actuali.services.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The response and its fully read body; reading inside the callback keeps the connection out of callers' hands. */
class HttpResult(val response: Response, val body: ByteArray) {
    val statusCode: Int get() = response.code

    val text: String get() = body.toString(Charsets.UTF_8)
}

/** Runs the call without blocking a thread, cancelling it if the coroutine is cancelled. */
suspend fun Call.await(): HttpResult = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val body = try {
                    response.use { it.body.bytes() }
                } catch (e: IOException) {
                    continuation.resumeWithException(e)
                    return
                }
                continuation.resume(HttpResult(response, body))
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        },
    )
}
