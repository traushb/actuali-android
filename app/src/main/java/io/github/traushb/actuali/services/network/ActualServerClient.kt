package io.github.traushb.actuali.services.network

import io.github.traushb.actuali.services.AppJson
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.Logger
import io.github.traushb.actuali.services.boolOrNull
import io.github.traushb.actuali.services.diagnostics.DiagnosticLog
import io.github.traushb.actuali.services.stringOrNull
import io.github.traushb.actuali.services.sync.ServerKeyInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

private val logger = Logger("ServerNetwork")

sealed class ActualServerError : Exception() {
    object InvalidURL : ActualServerError() {
        override val message: String get() = L("Invalid server URL")
    }

    object InvalidFallbackURL : ActualServerError() {
        override val message: String get() = L("Invalid fallback server URL")
    }

    object InvalidResponse : ActualServerError() {
        override val message: String get() = L("Invalid response from server")
    }

    data class HttpError(val statusCode: Int, val detail: String?) : ActualServerError() {
        override val message: String get() = L("HTTP error %lld: %@", statusCode.toLong(), detail ?: L("Unknown error"))
    }

    object Unauthorized : ActualServerError() {
        override val message: String get() = L("Unauthorized - please log in again")
    }

    data class NetworkError(val error: IOException) : ActualServerError() {
        override val message: String get() = connectionFailureMessage(error)
    }

    data class DecodingError(val error: Throwable) : ActualServerError() {
        override val message: String get() = L("Failed to decode response: %@", error.message ?: error.toString())
    }

    object FileNotFound : ActualServerError() {
        override val message: String get() = L("Budget file not found")
    }

    object AuthProxyBlocked : ActualServerError() {
        override val message: String
            get() = L(
                "The server responded with a login page instead of data — it looks like it's behind an authentication proxy (e.g. Cloudflare Access). Add the proxy's credentials under Custom HTTP headers, then try again.",
            )
    }

    /**
     * Whether the server couldn't be reached at all, as opposed to answering
     * with something unusable. Callers that fall back to a degraded mode on
     * failure use this to tell "old server" apart from "no server".
     */
    val isConnectionFailure: Boolean get() = this is NetworkError

    companion object {
        /** Where the troubleshooting steps for each of these live. */
        private const val HELP_LINK = "actuali.mfazz.com/support"

        /**
         * Turns a transport failure into something a non-technical user can
         * act on, rather than the platform's raw exception text.
         */
        private fun Throwable.hasCause(predicate: (Throwable) -> Boolean): Boolean =
            generateSequence(this) { it.cause }.take(10).any(predicate)

        fun connectionFailureMessage(error: IOException): String = when {
            error is SSLException && error.hasCause { it is CertificateExpiredException || it is CertificateNotYetValidException } ->
                L("Your server's security certificate has expired, so Android blocked the connection. Renewing the certificate on the server will fix this. See %@ for help.", HELP_LINK)
            error is SSLException ->
                L("Couldn't make a secure connection to your server — Android doesn't trust its security certificate. This usually means the server is using its own self-signed certificate. See %@ for how to fix it.", HELP_LINK)
            error is UnknownServiceException && error.message?.contains("CLEARTEXT", ignoreCase = true) == true ->
                L("Actuali can only connect over a secure address. Check that your server URL starts with https:// — see %@ for help.", HELP_LINK)
            error is UnknownHostException ->
                L("Couldn't find a server at that address. Check the server URL for typos. See %@ for help.", HELP_LINK)
            error is ConnectException || error is NoRouteToHostException ->
                L("Couldn't reach your server. If it's only available on your home network, connect to that network and try again. See %@ for help.", HELP_LINK)
            error is SocketTimeoutException ->
                L("Your server took too long to respond. It may be offline, or blocked on this network. See %@ for help.", HELP_LINK)
            else -> L("Couldn't connect to your server: %@ See %@ for help.", error.message ?: error.toString(), HELP_LINK)
        }
    }
}

/** A login method advertised by `GET /account/login-methods`, e.g. `password` or `openid`. */
@Serializable
data class LoginMethod(
    val method: String,
    val displayName: String? = null,
    /** SQLite stores this as 0/1; decoded tolerantly as an integer. */
    val active: Int? = null,
) {
    val isActive: Boolean get() = (active ?: 0) != 0
}

@Serializable
data class RemoteFile(
    val fileId: String,
    val groupId: String? = null,
    val name: String,
    val deleted: Int = 0,
    val encryptKeyId: String? = null,
)

@Serializable
data class FileInfo(
    val fileId: String,
    val groupId: String? = null,
    val name: String,
    val deleted: Int = 0,
    val encryptMeta: EncryptMeta? = null,
) {
    @Serializable
    data class EncryptMeta(val keyId: String, val algorithm: String? = null, val iv: String? = null, val authTag: String? = null)
}

// Bank sync (server-hosted SimpleFIN)

/**
 * An error the `/simplefin/…` routes report. They answer HTTP 200 and put
 * the failure in `data`, so a status-code check alone never sees these.
 */
data class ServerBankSyncError(val errorType: String?, val errorCode: String?, val reason: String?) {
    /**
     * Actual's `bank_sync_status` vocabulary for this failure — the same
     * mapping upstream's `getBankSyncStatusFromError` applies.
     */
    val bankSyncStatus: String
        get() = when (errorCode) {
            "ITEM_LOGIN_REQUIRED", "INVALID_ACCESS_TOKEN" -> "reauth-required"
            "ACCOUNT_NEEDS_ATTENTION" -> "attention-required"
            "RATE_LIMIT_EXCEEDED" -> "rate-limit-exceeded"
            "TIMED_OUT" -> "timed-out"
            "ACCOUNT_MISSING" -> "account-missing"
            else -> "failed"
        }

    companion object {
        fun from(json: JsonObject) = ServerBankSyncError(json["error_type"].stringOrNull, json["error_code"].stringOrNull, json["reason"].stringOrNull)
    }
}

/** One account's slice of a `/simplefin/transactions` response. */
data class ServerBankSyncAccountDownload(
    /**
     * The account's balance *as of now*, in cents, despite the name — see
     * upstream `processBankSyncDownload`.
     */
    val startingBalance: Long?,
    val transactions: List<ServerBankSyncTransaction>?,
)

/**
 * The server's already-normalized transaction shape — not raw SimpleFIN. The
 * bridge's own fields have been renamed and its dates turned into
 * `YYYY-MM-DD` strings by the time they reach us.
 */
data class ServerBankSyncTransaction(
    val transactionId: String?,
    val date: String?,
    val payeeName: String?,
    val notes: String?,
    val booked: Boolean?,
    val amount: String?,
)

/**
 * A whole `/simplefin/transactions` response body, keyed by account id with
 * `errors` alongside. [failure] is set when the request failed as a whole, in
 * which case `data` *is* the error object rather than containing one.
 */
data class ServerBankSyncDownloads(
    val accounts: Map<String, ServerBankSyncAccountDownload> = emptyMap(),
    val errors: Map<String, List<ServerBankSyncError>> = emptyMap(),
    val failure: ServerBankSyncError? = null,
) {
    companion object {
        fun from(json: JsonObject): ServerBankSyncDownloads {
            // A whole-request failure only counts as one when it names a code.
            val failure = ServerBankSyncError.from(json).takeIf { it.errorCode != null }
            val accounts = LinkedHashMap<String, ServerBankSyncAccountDownload>()
            var errors: Map<String, List<ServerBankSyncError>> = emptyMap()
            for ((key, value) in json) {
                when (key) {
                    "errors" -> errors = (value as? JsonObject)?.mapNotNull { (accountId, list) ->
                        val array = list as? JsonArray ?: return@mapNotNull null
                        accountId to array.mapNotNull { (it as? JsonObject)?.let(ServerBankSyncError::from) }
                    }?.toMap().orEmpty()
                    "error_type", "error_code", "reason", "status" -> Unit
                    else -> {
                        // An account the bridge didn't return comes back as null
                        // and simply doesn't contribute an entry.
                        val account = value as? JsonObject ?: continue
                        val all = ((account["transactions"] as? JsonObject)?.get("all") as? JsonArray)?.mapNotNull { element ->
                            val tx = element as? JsonObject ?: return@mapNotNull null
                            ServerBankSyncTransaction(
                                transactionId = tx["transactionId"].stringOrNull,
                                date = tx["date"].stringOrNull,
                                payeeName = tx["payeeName"].stringOrNull,
                                notes = tx["notes"].stringOrNull,
                                booked = tx["booked"].boolOrNull,
                                amount = (tx["transactionAmount"] as? JsonObject)?.get("amount").stringOrNull,
                            )
                        }
                        accounts[key] = ServerBankSyncAccountDownload(
                            startingBalance = (account["startingBalance"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong(),
                            transactions = all,
                        )
                    }
                }
            }
            return ServerBankSyncDownloads(accounts, errors, failure)
        }
    }
}

/** Version gate for features that depend on the server's Actual release. */
object ServerVersion {
    /**
     * payee_locations shipped in Actual 26.4.0. Writing those CRDT messages
     * against an older server breaks its web client with invalid-schema sync
     * errors, so suppress writes unless the server is known to be new enough.
     */
    fun supportsPayeeLocations(version: String?): Boolean {
        val parts = version?.split(".")?.map { it.toIntOrNull() } ?: return false
        if (parts.size < 2) return false
        val major = parts[0] ?: return false
        val minor = parts[1] ?: return false
        return major > 26 || (major == 26 && minor >= 4)
    }
}

/**
 * The Actual sync server's HTTP API. Requests that can't reach the configured
 * primary address retry once against the optional fallback address and stay
 * there until [retryPrimaryIfRecovered] finds the primary again.
 *
 * Mutable state lives behind one lock and is only read or swapped between
 * awaits, which is what the Swift actor's isolation provided.
 */
class ActualServerClient(
    client: OkHttpClient? = null,
    private val diagnosticLog: DiagnosticLog = DiagnosticLog.shared,
    /** Timeout for the [retryPrimaryIfRecovered] probe, short so a foreground probe never blocks. */
    private val probeTimeoutMillis: Long = 5000,
) {
    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .build()

    private val lock = Any()
    private var serverURL: HttpUrl? = null
    private var fallbackServerURL: HttpUrl? = null

    /** The primary as the user configured it, unaffected by failover swaps. */
    private var configuredPrimaryURL: HttpUrl? = null
    private var token: String? = null

    /**
     * User-supplied headers stamped onto every outgoing request, in order, to
     * authenticate through reverse-proxy layers (e.g. Cloudflare Access
     * service tokens). Applied before the client's own headers so app headers
     * like `X-ACTUAL-TOKEN` always take precedence.
     */
    private var customHeaders: List<Pair<String, String>> = emptyList()

    // Configuration

    fun configure(serverURL: String, fallbackServerURL: String = "") {
        val url = serverURL.toHttpUrlOrNull() ?: throw ActualServerError.InvalidURL
        val session = diagnosticLog.currentSessionID()
        // Set the primary before validating the fallback: a bad fallback must
        // not leave the client without a working primary.
        val headersConfigured = synchronized(lock) {
            this.serverURL = url
            configuredPrimaryURL = url
            this.fallbackServerURL = null
            customHeaders.isNotEmpty()
        }
        if (fallbackServerURL.isNotEmpty()) {
            val fallback = fallbackServerURL.toHttpUrlOrNull() ?: throw ActualServerError.InvalidFallbackURL
            synchronized(lock) { this.fallbackServerURL = fallback }
        }
        diagnosticLog.recordServerConfiguration(url.scheme, synchronized(lock) { this.fallbackServerURL != null }, session)
        diagnosticLog.recordCustomHeadersConfigured(headersConfigured, session)
    }

    fun setToken(token: String?) {
        val session = diagnosticLog.currentSessionID()
        synchronized(lock) { this.token = token }
        diagnosticLog.recordCredentialAvailability(token != null, session)
    }

    fun setCustomHeaders(headers: List<Pair<String, String>>) {
        val session = diagnosticLog.currentSessionID()
        synchronized(lock) { customHeaders = headers }
        diagnosticLog.recordCustomHeadersConfigured(headers.isNotEmpty(), session)
    }

    val isConfigured: Boolean get() = synchronized(lock) { serverURL != null }

    val isAuthenticated: Boolean get() = synchronized(lock) { token != null }

    private fun requireServer(): HttpUrl = synchronized(lock) { serverURL } ?: throw ActualServerError.InvalidURL

    private fun requireToken(): String = synchronized(lock) { token } ?: throw ActualServerError.Unauthorized

    /** `URL.appendingPathComponent`: [path] joined under the base URL's own path. */
    private fun HttpUrl.appending(path: String): HttpUrl = newBuilder().addPathSegments(path.trimStart('/')).build()

    /**
     * A request builder with the user's custom headers already applied.
     * Callers then set method-specific headers (which override any same-named
     * custom header) and the body.
     */
    private fun makeRequest(url: HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url)
        for ((name, value) in synchronized(lock) { customHeaders }) {
            runCatching { builder.header(name, value) }
        }
        return builder
    }

    /**
     * Remove user-configured server path prefixes from diagnostic paths: the
     * report identifies the Actual endpoint without exposing a custom server
     * path that may contain private routing information.
     */
    private fun diagnosticPath(url: HttpUrl, base: HttpUrl?): String {
        val path = url.encodedPath
        val prefix = base?.encodedPath?.trim('/') ?: ""
        if (prefix.isEmpty()) return path
        val normalizedPrefix = "/$prefix"
        if (!path.startsWith("$normalizedPrefix/")) return "/"
        return path.removePrefix(normalizedPrefix)
    }

    /**
     * Whether a response looks like an auth proxy's login page rather than the
     * Actual server's JSON — an HTML page or a redirect landing on Cloudflare
     * Access, which would otherwise surface as a cryptic decoding error.
     */
    private fun looksLikeAuthProxy(result: HttpResult): Boolean {
        if (result.response.request.url.host.lowercase().contains("cloudflareaccess.com")) return true
        val contentType = result.response.header("Content-Type")?.lowercase() ?: return false
        return contentType.contains("text/html") && result.body.isNotEmpty()
    }

    private fun record(request: Request, path: String, result: HttpResult?, error: DiagnosticLog.NetworkError?, expected: Set<Int>, session: java.util.UUID, startedAt: Long) {
        diagnosticLog.recordNetworkRequest(
            method = request.method,
            path = path,
            statusCode = result?.statusCode,
            error = error,
            expectedStatusCodes = expected,
            durationMilliseconds = System.currentTimeMillis() - startedAt,
            session = session,
        )
    }

    /**
     * Perform a request, translating transport failures into
     * [ActualServerError.NetworkError] and failing over to the fallback
     * address when the primary can't be reached.
     */
    private suspend fun send(request: Request, expected: Set<Int> = setOf(200)): HttpResult {
        val (requestServerURL, requestFallbackURL) = synchronized(lock) { serverURL to fallbackServerURL }
        val path = diagnosticPath(request.url, requestServerURL)
        val session = diagnosticLog.currentSessionID()
        val startedAt = System.currentTimeMillis()
        try {
            val result = http.newCall(request).await()
            record(request, path, result, if (looksLikeAuthProxy(result)) DiagnosticLog.NetworkError.AuthProxy else null, expected, session, startedAt)
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            record(request, path, null, DiagnosticLog.NetworkError.from(e), expected, session, startedAt)
            // ponytail: iOS skips the fallback when the device has no internet
            // at all; the exception alone can't tell that apart from a bad host
            // here, so the fallback is always tried — offline it fails fast too.
            val fallbackURL = if (requestFallbackURL != null && requestServerURL != null &&
                request.url.host == requestServerURL.host
            ) {
                fallbackRequestURL(request.url, requestServerURL, requestFallbackURL)?.takeIf { it != request.url }
            } else {
                null
            }
            if (fallbackURL == null) throw ActualServerError.NetworkError(e)

            val fallbackRequest = request.newBuilder().url(fallbackURL).build()
            val fallbackStartedAt = System.currentTimeMillis()
            try {
                val result = http.newCall(fallbackRequest).await()
                record(fallbackRequest, path, result, if (looksLikeAuthProxy(result)) DiagnosticLog.NetworkError.AuthProxy else null, expected, session, fallbackStartedAt)
                val swapped = synchronized(lock) {
                    if (serverURL == requestServerURL) {
                        serverURL = requestFallbackURL
                        fallbackServerURL = requestServerURL
                        true
                    } else {
                        false
                    }
                }
                if (swapped) {
                    diagnosticLog.recordServerRoute(if (synchronized(lock) { serverURL == configuredPrimaryURL }) "primary" else "fallback", session)
                }
                logger.notice("Server was unreachable; switched to the configured alternate address")
                return result
            } catch (fallbackError: CancellationException) {
                throw fallbackError
            } catch (fallbackError: IOException) {
                record(fallbackRequest, path, null, DiagnosticLog.NetworkError.from(fallbackError), expected, session, fallbackStartedAt)
                logger.error("Fallback address also failed: ${fallbackError.javaClass.simpleName}")
                throw ActualServerError.NetworkError(e)
            }
        }
    }

    /**
     * After a failover, check whether the configured primary is reachable
     * again and swap back if so. Safe to fire-and-forget on foreground: the
     * short probe timeout never blocks callers' own requests.
     */
    suspend fun retryPrimaryIfRecovered() {
        val (primary, current) = synchronized(lock) { configuredPrimaryURL to serverURL }
        if (primary == null || current == null || current == primary) return
        // Older servers and route-stripping reverse proxies don't answer /info,
        // so any client-side status proves the primary is reachable; 5xx means
        // a proxy whose backend is down, so stay on the fallback.
        val request = makeRequest(primary.appending("/info")).get().build()
        val session = diagnosticLog.currentSessionID()
        val startedAt = System.currentTimeMillis()
        val expected = (200 until 500).toSet()
        val probe = http.newBuilder().callTimeout(probeTimeoutMillis, TimeUnit.MILLISECONDS).build()
        try {
            val result = probe.newCall(request).await()
            record(request, "/info", result, null, expected, session, startedAt)
            if (result.statusCode !in 200 until 500) return
        } catch (e: CancellationException) {
            return
        } catch (e: IOException) {
            record(request, "/info", null, DiagnosticLog.NetworkError.from(e), expected, session, startedAt)
            return
        }
        // Re-check: a concurrent request may have swapped meanwhile.
        val swapped = synchronized(lock) {
            if (serverURL == current) {
                fallbackServerURL = current
                serverURL = primary
                true
            } else {
                false
            }
        }
        if (swapped) {
            diagnosticLog.recordServerRoute("primary", session)
            logger.notice("Primary server is reachable again; switched back to it")
        }
    }

    // Authentication

    private val jsonType = "application/json".toMediaType()

    private fun jsonBody(vararg fields: Pair<String, String>) =
        buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }.toString().toRequestBody(jsonType)

    private inline fun <T> decode(result: HttpResult, parse: (JsonElement) -> T): T = try {
        parse(AppJson.parseToJsonElement(result.text))
    } catch (e: ActualServerError) {
        throw e
    } catch (e: Exception) {
        throw ActualServerError.DecodingError(e)
    }

    suspend fun login(password: String): String {
        val request = makeRequest(requireServer().appending("/account/login")).post(jsonBody("password" to password)).build()
        val result = send(request)
        if (result.statusCode == 400) throw ActualServerError.Unauthorized
        if (result.statusCode != 200) throw ActualServerError.HttpError(result.statusCode, result.text)
        if (looksLikeAuthProxy(result)) throw ActualServerError.AuthProxyBlocked
        val token = decode(result) { json ->
            val obj = json as JsonObject
            if (obj["status"].stringOrNull != "ok") null else (obj["data"] as? JsonObject)?.get("token").stringOrNull
        } ?: throw ActualServerError.Unauthorized
        setToken(token)
        return token
    }

    /**
     * Discover which login methods the server supports via
     * `GET /account/login-methods`. Returns every method the server reports,
     * including inactive ones — callers need to know a password fallback
     * *exists* even when it isn't the active method. Older servers without
     * this endpoint (404) are treated as password-only.
     */
    suspend fun fetchLoginMethods(): List<LoginMethod> {
        val request = makeRequest(requireServer().appending("/account/login-methods")).get().build()
        val result = send(request, setOf(200, 404))
        if (result.statusCode == 404) return listOf(LoginMethod("password", "Password", 1))
        if (result.statusCode != 200) throw ActualServerError.HttpError(result.statusCode, result.text)
        if (looksLikeAuthProxy(result)) throw ActualServerError.AuthProxyBlocked
        return decode(result) { json ->
            val obj = json as JsonObject
            val methods = obj["methods"] as? JsonArray
            if (obj["status"].stringOrNull != "ok" || methods == null) throw ActualServerError.InvalidResponse
            methods.map { AppJson.decodeFromJsonElement(LoginMethod.serializer(), it) }
        }
    }

    /**
     * Whether an account owner has been created yet (`GET /admin/owner-created/`,
     * a bare JSON boolean). Defaults to true on any failure so we don't nag
     * the user on servers where the endpoint is unavailable.
     */
    suspend fun fetchOwnerCreated(): Boolean {
        val server = synchronized(lock) { serverURL } ?: return true
        return try {
            val result = send(makeRequest(server.appending("/admin/owner-created/")).get().build(), setOf(200, 404))
            if (result.statusCode != 200) true else result.text.trim().toBooleanStrictOrNull() ?: true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            true
        }
    }

    /**
     * `GET /info` — the sync server's build metadata. Null on any failure;
     * callers treat null as "capabilities unknown".
     */
    suspend fun fetchServerVersion(): String? {
        val server = synchronized(lock) { serverURL } ?: return null
        return try {
            val result = send(makeRequest(server.appending("/info")).get().build(), setOf(200, 404))
            if (result.statusCode != 200) return null
            ((AppJson.parseToJsonElement(result.text) as? JsonObject)?.get("build") as? JsonObject)?.get("version").stringOrNull
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Begin an OpenID login by POSTing to `/account/login` with
     * `loginMethod = "openid"`. The server validates [returnURL] (its hostname
     * must match the server or be `localhost`) and returns the provider's
     * authorization URL to open in a browser.
     * @param firstTimePassword required only when the server also has password
     *   auth configured and no named users exist yet (first login).
     */
    suspend fun beginOpenIDLogin(returnURL: String, firstTimePassword: String?): String {
        val fields = mutableListOf("loginMethod" to "openid", "returnUrl" to returnURL)
        if (!firstTimePassword.isNullOrEmpty()) fields += "password" to firstTimePassword
        val request = makeRequest(requireServer().appending("/account/login")).post(jsonBody(*fields.toTypedArray())).build()
        val result = send(request)
        if (result.statusCode != 200) {
            // Surface the server's `reason` (e.g. invalid-password, invalid-return-url) when present.
            val reason = runCatching { (AppJson.parseToJsonElement(result.text) as JsonObject)["reason"].stringOrNull }.getOrNull()
            throw ActualServerError.HttpError(result.statusCode, reason ?: result.text)
        }
        return decode(result) { json ->
            val obj = json as JsonObject
            val url = (obj["data"] as? JsonObject)?.get("returnUrl").stringOrNull
            if (obj["status"].stringOrNull != "ok" || url == null) throw ActualServerError.InvalidResponse
            url
        }
    }

    // Files

    suspend fun listFiles(): List<RemoteFile> {
        val request = makeRequest(requireServer().appending("/sync/list-user-files")).get().header("X-ACTUAL-TOKEN", requireToken()).build()
        val result = send(request)
        if (result.statusCode == 403) throw ActualServerError.Unauthorized
        if (result.statusCode != 200) throw ActualServerError.HttpError(result.statusCode, result.text)
        val files = decode(result) { json ->
            val obj = json as JsonObject
            val data = obj["data"] as? JsonArray
            if (obj["status"].stringOrNull != "ok" || data == null) throw ActualServerError.InvalidResponse
            data.map { AppJson.decodeFromJsonElement(RemoteFile.serializer(), it) }
        }
        // Filter out deleted files
        return files.filter { it.deleted == 0 }
    }

    suspend fun downloadFile(fileId: String): ByteArray {
        val request = makeRequest(requireServer().appending("/sync/download-user-file")).get()
            .header("X-ACTUAL-TOKEN", requireToken())
            .header("X-ACTUAL-FILE-ID", fileId)
            .build()
        val result = send(request)
        if (result.statusCode == 403) throw ActualServerError.Unauthorized
        if (result.statusCode == 400 || result.statusCode == 404) throw ActualServerError.FileNotFound
        if (result.statusCode != 200) throw ActualServerError.HttpError(result.statusCode, result.text)
        return result.body
    }

    /**
     * Upload a budget archive to `/sync/upload-user-file`, registering it on
     * the server. Returns the sync group id the server assigned (a fresh one
     * when no `X-ACTUAL-GROUP-ID` is sent, as for a newly created file).
     * Mirrors upstream's upload minus the encryption branch — the app only
     * creates unencrypted files.
     */
    suspend fun uploadFile(zipData: ByteArray, fileId: String, name: String): String {
        val request = makeRequest(requireServer().appending("/sync/upload-user-file"))
            .post(zipData.toRequestBody("application/encrypted-file".toMediaType()))
            .header("X-ACTUAL-TOKEN", requireToken())
            .header("X-ACTUAL-FILE-ID", fileId)
            // Upstream sends encodeURIComponent(budgetName).
            .header("X-ACTUAL-NAME", encodeURIComponent(name))
            .header("X-ACTUAL-FORMAT", "2")
            .build()
        val result = send(request)
        if (result.statusCode == 401 || result.statusCode == 403) throw ActualServerError.Unauthorized
        if (result.statusCode != 200) throw ActualServerError.HttpError(result.statusCode, result.text)
        return decode(result) { json ->
            val obj = json as JsonObject
            val groupId = obj["groupId"].stringOrNull
            if (obj["status"].stringOrNull != "ok" || groupId == null) throw ActualServerError.InvalidResponse
            groupId
        }
    }

    suspend fun getFileInfo(fileId: String): FileInfo {
        val request = makeRequest(requireServer().appending("/sync/get-user-file-info")).get()
            .header("X-ACTUAL-TOKEN", requireToken())
            .header("X-ACTUAL-FILE-ID", fileId)
            .build()
        val result = send(request)
        if (result.statusCode == 403) throw ActualServerError.Unauthorized
        if (result.statusCode != 200) throw ActualServerError.HttpError(result.statusCode, result.text)
        return decode(result) { json ->
            val obj = json as JsonObject
            val data = obj["data"]
            if (obj["status"].stringOrNull != "ok" || data !is JsonObject) throw ActualServerError.FileNotFound
            AppJson.decodeFromJsonElement(FileInfo.serializer(), data)
        }
    }

    /**
     * `POST /sync/delete-user-file` — marks the file deleted on the server for
     * every client. Callers treat [ActualServerError.FileNotFound] as "already
     * deleted" and destroy the local copy, so only the Actual server's own
     * answer may map to it.
     */
    suspend fun deleteFile(fileId: String) {
        val token = requireToken()
        val request = makeRequest(requireServer().appending("/sync/delete-user-file"))
            .post(jsonBody("token" to token, "fileId" to fileId))
            .header("X-ACTUAL-TOKEN", token)
            .build()
        val result = send(request)
        if (looksLikeAuthProxy(result)) throw ActualServerError.AuthProxyBlocked
        if (result.statusCode == 403) throw ActualServerError.Unauthorized
        if (result.statusCode == 400 && result.text == "file-not-found") throw ActualServerError.FileNotFound
        if (result.statusCode != 200) throw ActualServerError.HttpError(result.statusCode, result.text)
    }

    suspend fun getKeyInfo(fileId: String): ServerKeyInfo {
        val token = requireToken()
        val request = makeRequest(requireServer().appending("/sync/user-get-key"))
            .post(jsonBody("token" to token, "fileId" to fileId))
            .header("X-ACTUAL-TOKEN", token)
            .build()
        val result = send(request)
        if (result.statusCode == 403) throw ActualServerError.Unauthorized
        if (result.statusCode != 200) throw ActualServerError.HttpError(result.statusCode, result.text)
        return decode(result) { json ->
            val obj = json as JsonObject
            val data = obj["data"] as? JsonObject
            if (obj["status"].stringOrNull != "ok" || data == null) throw ActualServerError.InvalidResponse
            AppJson.decodeFromJsonElement(ServerKeyInfo.serializer(), data)
        }
    }

    // Bank sync (server-hosted SimpleFIN)

    /**
     * Whether the server has its own SimpleFIN credentials, or null when it has
     * no `/simplefin` routes at all. Callers treat null as "this server can't
     * do bank sync" and fall back to the device's own key — which is why a
     * route that isn't there and a server that can't be reached must not look
     * the same: the latter throws.
     */
    suspend fun simpleFINStatus(): Boolean? =
        postBankSync("/simplefin/status", JsonObject(emptyMap()))?.let { ((it["data"] as? JsonObject)?.get("configured")).boolOrNull }

    /** Every account the server's SimpleFIN connection covers, in the raw bridge shape. */
    suspend fun simpleFINAccounts(): List<SimpleFINAccount> {
        val response = postBankSync("/simplefin/accounts", JsonObject(emptyMap())) ?: throw ActualServerError.InvalidResponse
        val data = response["data"] as? JsonObject
        val failure = data?.let(ServerBankSyncError::from)?.takeIf { it.errorCode != null }
        if (failure != null) throw ActualServerError.HttpError(200, failure.reason ?: failure.errorCode)
        return (data?.get("accounts") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(SimpleFINAccount::from) }.orEmpty()
    }

    /**
     * Transactions for the named accounts, each from its own `YYYY-MM-DD`
     * start date (one per account id, same length — the route rejects a
     * mismatch). Always the array form, even for one account: it keeps the
     * response shape the same.
     */
    suspend fun simpleFINTransactions(accountIds: List<String>, startDates: List<String>): ServerBankSyncDownloads {
        if (accountIds.size != startDates.size) throw ActualServerError.InvalidResponse
        val body = buildJsonObject {
            put("accountId", JsonArray(accountIds.map(::JsonPrimitive)))
            put("startDate", JsonArray(startDates.map(::JsonPrimitive)))
        }
        val response = postBankSync("/simplefin/transactions", body) ?: throw ActualServerError.InvalidResponse
        val data = response["data"] as? JsonObject ?: throw ActualServerError.InvalidResponse
        return ServerBankSyncDownloads.from(data)
    }

    /** Shared plumbing for the `/simplefin` routes. Null when the route isn't there; throws for everything else. */
    private suspend fun postBankSync(path: String, body: JsonObject): JsonObject? {
        val request = makeRequest(requireServer().appending(path))
            .post(body.toString().toRequestBody(jsonType))
            .header("X-ACTUAL-TOKEN", requireToken())
            .build()
        val result = send(request, setOf(200, 404, 405, 501))
        if (result.statusCode == 403) throw ActualServerError.Unauthorized
        // The route genuinely isn't served here — not a failure, just an older
        // server. 501 covers proxies that answer unimplemented paths that way.
        if (result.statusCode in setOf(404, 405, 501)) return null
        if (result.statusCode != 200) throw ActualServerError.HttpError(result.statusCode, result.text)
        return decode(result) { it as? JsonObject ?: throw ActualServerError.InvalidResponse }
    }

    // Sync

    suspend fun postSync(requestData: ByteArray): ByteArray {
        val request = makeRequest(requireServer().appending("/sync/sync"))
            .post(requestData.toRequestBody("application/actual-sync".toMediaType()))
            .header("X-ACTUAL-TOKEN", requireToken())
            .build()
        val result = send(request)
        if (result.statusCode == 403) throw ActualServerError.Unauthorized
        if (result.statusCode != 200) throw ActualServerError.HttpError(result.statusCode, result.text)
        return result.body
    }

    companion object {
        /**
         * Rebase an API request from the primary server onto the fallback while
         * preserving path prefixes on either address (for example `/actual`).
         */
        internal fun fallbackRequestURL(requestURL: HttpUrl, primaryServerURL: HttpUrl, fallbackServerURL: HttpUrl): HttpUrl? {
            val primaryPath = primaryServerURL.encodedPath.trim('/')
            val requestPath = requestURL.encodedPath.trim('/')
            val endpointPath = if (primaryPath.isNotEmpty() && (requestPath == primaryPath || requestPath.startsWith("$primaryPath/"))) {
                requestPath.substring(primaryPath.length)
            } else {
                requestPath
            }
            val fallbackPath = fallbackServerURL.encodedPath.trim('/')
            val endpoint = endpointPath.trim('/')
            val path = "/" + listOf(fallbackPath, endpoint).filter { it.isNotEmpty() }.joinToString("/")
            return fallbackServerURL.newBuilder().encodedPath(path).encodedQuery(requestURL.encodedQuery).build()
        }

        /** JS `encodeURIComponent`: escapes everything outside `A-Za-z0-9-_.!~*'()`. */
        fun encodeURIComponent(value: String): String {
            val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
            val out = StringBuilder()
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = (byte.toInt() and 0xff).toChar()
                if (byte >= 0 && c in unreserved) out.append(c) else out.append('%').append(String.format("%02X", byte.toInt() and 0xff))
            }
            return out.toString()
        }
    }
}
