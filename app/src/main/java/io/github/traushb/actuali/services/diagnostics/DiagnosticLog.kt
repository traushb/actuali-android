package io.github.traushb.actuali.services.diagnostics

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.UUID
import javax.net.ssl.SSLException
import kotlin.math.max
import kotlin.math.roundToLong

data class DiagnosticLogEntry(val date: Long = System.currentTimeMillis(), val message: String, val id: String = UUID.randomUUID().toString())

data class DiagnosticLogSnapshot(
    val entries: List<DiagnosticLogEntry>,
    val serverConfigured: Boolean,
    val serverTransport: String,
    val fallbackConfigured: Boolean,
    val serverRoute: String,
    val connectionStatus: DiagnosticLog.ConnectionStatus,
    val credentialPresent: Boolean,
    val customHeadersConfigured: Boolean,
    val lastNetworkStatusCode: Int?,
    val lastNetworkError: DiagnosticLog.NetworkError?,
    val lastSyncAttemptAt: Long?,
    val lastSyncSuccessAt: Long?,
    val lastSyncResult: DiagnosticLog.SyncResult?,
    val lastSyncDurationMilliseconds: Long?,
    val lastSyncRequestMessageCount: Int?,
    val lastSyncResponseMessageCount: Int?,
) {
    companion object {
        val empty = DiagnosticLogSnapshot(
            entries = emptyList(),
            serverConfigured = false,
            serverTransport = "not configured",
            fallbackConfigured = false,
            serverRoute = "none",
            connectionStatus = DiagnosticLog.ConnectionStatus.Unknown,
            credentialPresent = false,
            customHeadersConfigured = false,
            lastNetworkStatusCode = null,
            lastNetworkError = null,
            lastSyncAttemptAt = null,
            lastSyncSuccessAt = null,
            lastSyncResult = null,
            lastSyncDurationMilliseconds = null,
            lastSyncRequestMessageCount = null,
            lastSyncResponseMessageCount = null,
        )
    }
}

/**
 * A privacy-safe, in-memory record of connection and sync events for the
 * diagnostic report: methods, paths, status codes and counts — never bodies,
 * tokens or amounts. Every call is a short critical section, so a lock does
 * what the Swift actor does.
 */
class DiagnosticLog(capacity: Int = 300) {
    enum class ConnectionStatus(val rawValue: String) { Unknown("unknown"), Online("online"), Offline("offline") }

    sealed interface NetworkError {
        data object TimedOut : NetworkError
        data object NotConnectedToInternet : NetworkError
        data object DnsLookupFailed : NetworkError
        data object CannotFindHost : NetworkError
        data object CannotConnectToHost : NetworkError
        data object AuthProxy : NetworkError
        data class Http(val statusCode: Int) : NetworkError
        data class Tls(val code: Int) : NetworkError

        /** Android's cleartext policy refused a plain-HTTP server (iOS reports ATS). */
        data object CleartextBlocked : NetworkError
        data class Transport(val code: Int) : NetworkError

        val diagnosticDescription: String
            get() = when (this) {
                TimedOut -> "timeout"
                NotConnectedToInternet -> "no-internet"
                DnsLookupFailed -> "dns-lookup-failed"
                CannotFindHost -> "host-not-found"
                CannotConnectToHost -> "cannot-connect"
                AuthProxy -> "auth-proxy"
                is Http -> "http($statusCode)"
                is Tls -> "tls($code)"
                CleartextBlocked -> "cleartext-blocked"
                is Transport -> "transport($code)"
            }

        companion object {
            /** The URLError categories, read off the exception OkHttp raised. */
            fun from(error: IOException): NetworkError = when (error) {
                is SocketTimeoutException -> TimedOut
                is UnknownHostException -> CannotFindHost
                is ConnectException, is NoRouteToHostException -> CannotConnectToHost
                is SSLException -> Tls(0)
                is UnknownServiceException -> if (error.message?.contains("CLEARTEXT", ignoreCase = true) == true) CleartextBlocked else Transport(0)
                else -> Transport(0)
            }
        }
    }

    enum class SyncResult(val rawValue: String) { Success("success"), Offline("offline"), Cancelled("cancelled"), Failed("failed") }

    enum class ResyncReason(val rawValue: String) { MerkleMismatch("merkle-mismatch"), ManualReset("manual-reset") }

    private val capacity = max(1, capacity)
    private val lock = Any()
    private var sessionID = UUID.randomUUID()
    private val entries = ArrayDeque<DiagnosticLogEntry>()
    private var serverConfigured = false
    private var serverTransport = "not configured"
    private var fallbackConfigured = false
    private var serverRoute = "none"
    private var connectionStatus = ConnectionStatus.Unknown
    private var credentialPresent = false
    private var customHeadersConfigured = false
    private var lastNetworkStatusCode: Int? = null
    private var lastNetworkError: NetworkError? = null
    private var lastSyncAttemptAt: Long? = null
    private var lastSyncSuccessAt: Long? = null
    private var lastSyncResult: SyncResult? = null
    private var lastSyncDurationMilliseconds: Long? = null
    private var lastSyncRequestMessageCount: Int? = null
    private var lastSyncResponseMessageCount: Int? = null
    private var syncStartedAt: Long? = null

    fun currentSessionID(): UUID = synchronized(lock) { sessionID }

    /** Runs [block] only when [session] is null or still the current session. */
    private inline fun inSession(session: UUID?, block: () -> Unit) = synchronized(lock) {
        if (session == null || session == sessionID) block()
    }

    fun clear() = synchronized(lock) {
        sessionID = UUID.randomUUID()
        entries.clear()
        serverConfigured = false
        serverTransport = "not configured"
        fallbackConfigured = false
        serverRoute = "none"
        connectionStatus = ConnectionStatus.Unknown
        credentialPresent = false
        customHeadersConfigured = false
        lastNetworkStatusCode = null
        lastNetworkError = null
        lastSyncAttemptAt = null
        lastSyncSuccessAt = null
        lastSyncResult = null
        lastSyncDurationMilliseconds = null
        lastSyncRequestMessageCount = null
        lastSyncResponseMessageCount = null
        syncStartedAt = null
    }

    fun snapshot(): DiagnosticLogSnapshot = synchronized(lock) {
        DiagnosticLogSnapshot(
            entries = entries.toList(),
            serverConfigured = serverConfigured,
            serverTransport = serverTransport,
            fallbackConfigured = fallbackConfigured,
            serverRoute = serverRoute,
            connectionStatus = connectionStatus,
            credentialPresent = credentialPresent,
            customHeadersConfigured = customHeadersConfigured,
            lastNetworkStatusCode = lastNetworkStatusCode,
            lastNetworkError = lastNetworkError,
            lastSyncAttemptAt = lastSyncAttemptAt,
            lastSyncSuccessAt = lastSyncSuccessAt,
            lastSyncResult = lastSyncResult,
            lastSyncDurationMilliseconds = lastSyncDurationMilliseconds,
            lastSyncRequestMessageCount = lastSyncRequestMessageCount,
            lastSyncResponseMessageCount = lastSyncResponseMessageCount,
        )
    }

    fun recordServerConfiguration(transport: String, fallbackConfigured: Boolean, session: UUID? = null) = inSession(session) {
        serverConfigured = true
        serverTransport = transport.lowercase()
        this.fallbackConfigured = fallbackConfigured
        serverRoute = "primary"
        connectionStatus = ConnectionStatus.Unknown
        lastNetworkStatusCode = null
        lastNetworkError = null
        append("CONFIG server transport=$serverTransport fallback=${if (fallbackConfigured) "yes" else "no"}")
    }

    fun recordServerRoute(route: String, session: UUID? = null) = inSession(session) {
        if (route == "primary" || route == "fallback") {
            serverRoute = route
            append("ROUTE $route")
        }
    }

    fun recordCredentialAvailability(available: Boolean, session: UUID? = null) = inSession(session) { credentialPresent = available }

    fun recordCustomHeadersConfigured(configured: Boolean, session: UUID? = null) = inSession(session) { customHeadersConfigured = configured }

    fun recordNetworkRequest(
        method: String,
        path: String,
        statusCode: Int?,
        error: NetworkError?,
        expectedStatusCodes: Set<Int> = setOf(200),
        durationMilliseconds: Long,
        session: UUID? = null,
    ) = inSession(session) {
        lastNetworkStatusCode = statusCode
        connectionStatus = if (statusCode == null && error != null) ConnectionStatus.Offline else ConnectionStatus.Online
        val resolvedError = error ?: statusCode?.takeIf { it !in expectedStatusCodes }?.let { NetworkError.Http(it) }
        if (resolvedError != null) lastNetworkError = resolvedError
        append(
            "NETWORK method=$method path=$path status=${statusCode?.toString() ?: "none"} " +
                "error=${resolvedError?.diagnosticDescription ?: "none"} duration=${max(0, durationMilliseconds)}ms",
        )
    }

    fun recordSyncConfiguration(session: UUID? = null) = inSession(session) {
        lastSyncAttemptAt = null
        lastSyncSuccessAt = null
        lastSyncResult = null
        lastSyncDurationMilliseconds = null
        lastSyncRequestMessageCount = null
        lastSyncResponseMessageCount = null
        syncStartedAt = null
        append("CONFIG sync-budget")
    }

    fun recordSyncStarted(session: UUID? = null) = inSession(session) {
        val now = System.currentTimeMillis()
        lastSyncAttemptAt = now
        lastSyncResult = null
        lastSyncDurationMilliseconds = null
        lastSyncRequestMessageCount = null
        lastSyncResponseMessageCount = null
        syncStartedAt = now
        append("SYNC started")
    }

    fun recordSyncFinished(result: SyncResult, session: UUID? = null) = inSession(session) {
        val finishedAt = System.currentTimeMillis()
        lastSyncResult = result
        syncStartedAt?.let { lastSyncDurationMilliseconds = max(0, (finishedAt - it).toDouble().roundToLong()) }
        if (result == SyncResult.Success) lastSyncSuccessAt = finishedAt
        syncStartedAt = null
        append("SYNC finished result=${result.rawValue} duration=${lastSyncDurationMilliseconds ?: 0}ms")
    }

    fun recordSyncRequestMessageCount(count: Int, session: UUID? = null) = inSession(session) {
        lastSyncRequestMessageCount = max(0, count)
        append("SYNC request-messages=${max(0, count)}")
    }

    fun recordSyncResponseMessageCount(count: Int, session: UUID? = null) = inSession(session) {
        lastSyncResponseMessageCount = max(0, count)
        append("SYNC response-messages=${max(0, count)}")
    }

    fun recordMerkleMismatch(session: UUID? = null) = inSession(session) { append("SYNC merkle-mismatch") }

    fun recordResyncTriggered(reason: ResyncReason, session: UUID? = null) = inSession(session) {
        append("SYNC resync-triggered reason=${reason.rawValue}")
    }

    private fun append(message: String) {
        entries.addLast(DiagnosticLogEntry(message = message))
        while (entries.size > capacity) entries.removeFirst()
    }

    companion object {
        val shared = DiagnosticLog()
    }
}
