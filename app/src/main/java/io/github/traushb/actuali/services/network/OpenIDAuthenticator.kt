package io.github.traushb.actuali.services.network

import io.github.traushb.actuali.services.L
import kotlinx.coroutines.CompletableDeferred
import java.net.URI
import java.net.URLDecoder

sealed class OpenIDAuthError : Exception() {
    object Cancelled : OpenIDAuthError() {
        override val message: String get() = L("Sign-in was cancelled")
    }

    object MissingToken : OpenIDAuthError() {
        override val message: String get() = L("The server did not return a sign-in token")
    }

    object NoWindow : OpenIDAuthError() {
        override val message: String get() = L("Sign-in needs an open window. Try again with the app in the foreground.")
    }

    data class Server(val reason: String) : OpenIDAuthError() {
        override val message: String get() = L("Sign-in failed: %@", reason)
    }

    data class SessionFailed(val error: Throwable) : OpenIDAuthError() {
        override val message: String get() = L("Sign-in failed: %@", error.localizedMessage ?: L("Could not start the sign-in session"))
    }
}

/**
 * Drives the OpenID/OAuth browser flow in a Custom Tab.
 *
 * The Actual server validates the `returnUrl` we hand it: its host must equal
 * the server's host or be `localhost`. A custom scheme whose host is
 * `localhost` (`actuali://localhost`) satisfies that check while remaining
 * something the app can intercept: after the provider callback, the server
 * redirects to `actuali://localhost/openid-cb?token=…`, which MainActivity
 * hands to [handleCallback].
 *
 * Unlike iOS's ASWebAuthenticationSession, a Custom Tab doesn't report being
 * closed; the app coming back to the foreground without a callback is that
 * signal ([onPaused]/[onResumed]).
 */
object OpenIDAuthenticator {
    const val CALLBACK_SCHEME = "actuali"
    const val RETURN_URL = "$CALLBACK_SCHEME://localhost"

    /** Opens a URL in the browser; installed by the foreground activity, null while none is. Returns whether it could. */
    @Volatile
    var launcher: ((String) -> Boolean)? = null

    private var pending: CompletableDeferred<String>? = null
    private var leftForBrowser = false

    /** Present the provider's authorization page and wait for the callback's Actual session token. */
    suspend fun authenticate(authorizationURL: String): String {
        val launch = launcher ?: throw OpenIDAuthError.NoWindow
        val deferred = CompletableDeferred<String>()
        pending?.completeExceptionally(OpenIDAuthError.Cancelled)
        pending = deferred
        leftForBrowser = false
        if (!launch(authorizationURL)) {
            pending = null
            throw OpenIDAuthError.SessionFailed(IllegalStateException(L("Could not start the sign-in session")))
        }
        try {
            return deferred.await()
        } finally {
            if (pending === deferred) pending = null
        }
    }

    /** A deep link that reached the app; true when it was the sign-in callback. */
    fun handleCallback(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme != CALLBACK_SCHEME) return false
        val deferred = pending ?: return true
        try {
            deferred.complete(extractToken(url))
        } catch (e: OpenIDAuthError) {
            deferred.completeExceptionally(e)
        }
        return true
    }

    fun onPaused() {
        if (pending != null) leftForBrowser = true
    }

    /** Back in the app without a callback: the person closed the browser. */
    fun onResumed() {
        if (!leftForBrowser) return
        leftForBrowser = false
        pending?.completeExceptionally(OpenIDAuthError.Cancelled)
    }

    /** The `token` (or `error`) query item of the server's callback URL, e.g. `actuali://localhost/openid-cb?token=…`. */
    fun extractToken(url: String): String {
        val query = runCatching { URI(url).rawQuery }.getOrNull().orEmpty()
        val items = query.split("&").filter { it.isNotEmpty() }.associate { item ->
            val name = item.substringBefore("=")
            val value = item.substringAfter("=", "")
            URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }
        items["error"]?.takeIf { it.isNotEmpty() }?.let { throw OpenIDAuthError.Server(it) }
        return items["token"]?.takeIf { it.isNotEmpty() } ?: throw OpenIDAuthError.MissingToken
    }
}
