package io.github.traushb.actuali.services.network

import io.github.traushb.actuali.services.AppJson
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.Logger
import io.github.traushb.actuali.services.boolOrNull
import io.github.traushb.actuali.services.longOrNullValue
import io.github.traushb.actuali.services.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

private val logger = Logger("SimpleFIN")

sealed class SimpleFINError : Exception() {
    /** The pasted setup token isn't base64, or doesn't decode to an https URL. */
    object InvalidSetupToken : SimpleFINError() {
        override val message: String
            get() = L("That doesn't look like a SimpleFIN setup token. Copy the whole token from SimpleFIN and paste it again.")
    }

    /** The stored access key isn't in `scheme://user:pass@host/path` form. */
    object InvalidAccessKey : SimpleFINError() {
        override val message: String
            get() = L("The stored SimpleFIN access key is unreadable. Disconnect SimpleFIN and set it up again.")
    }

    /**
     * The bridge refused the claim — setup tokens are single-use, so this
     * usually means the token was already claimed.
     */
    object ClaimRejected : SimpleFINError() {
        override val message: String
            get() = L("SimpleFIN rejected this setup token. Tokens can only be claimed once — generate a new one at SimpleFIN and paste that.")
    }

    /** Basic auth was rejected: the access key is no longer valid. */
    object Forbidden : SimpleFINError() {
        override val message: String
            get() = L("SimpleFIN rejected the saved access key. Disconnect SimpleFIN and set it up again with a new setup token.")
    }

    data class HttpError(val statusCode: Int) : SimpleFINError() {
        override val message: String get() = L("SimpleFIN returned an unexpected response (HTTP %lld).", statusCode.toLong())
    }

    object InvalidResponse : SimpleFINError() {
        override val message: String get() = L("SimpleFIN returned a response Actuali couldn't read.")
    }

    data class NetworkError(val detail: String) : SimpleFINError() {
        override val message: String get() = L("Couldn't reach SimpleFIN: %@", detail)
    }
}

/**
 * The long-lived credential a claimed setup token yields, in SimpleFIN's
 * `https://user:password@bridge.example.com/simplefin` form. Parsed by hand:
 * the bridge's generated passwords are arbitrary strings that URL parsers
 * refuse or silently mangle.
 */
data class SimpleFINAccessKey(
    /** The key exactly as SimpleFIN issued it, stored and read back byte for byte. */
    val raw: String,
    val baseURL: HttpUrl,
    val username: String,
    val password: String,
) {
    /** The `Authorization` header value every request carries. */
    val basicAuthHeader: String
        get() = "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))

    companion object {
        fun parse(raw: String): SimpleFINAccessKey {
            val trimmed = raw.trim()
            val schemeEnd = trimmed.indexOf("://").takeIf { it >= 0 } ?: throw SimpleFINError.InvalidAccessKey
            val scheme = trimmed.substring(0, schemeEnd)
            val rest = trimmed.substring(schemeEnd + 3)
            // Split on the *last* "@": the password may legitimately contain one, the host may not.
            val atIndex = rest.lastIndexOf('@').takeIf { it >= 0 } ?: throw SimpleFINError.InvalidAccessKey
            val auth = rest.substring(0, atIndex)
            val host = rest.substring(atIndex + 1)
            // Split on the *first* ":" for the same reason, in reverse.
            val colonIndex = auth.indexOf(':').takeIf { it >= 0 } ?: throw SimpleFINError.InvalidAccessKey
            val username = auth.substring(0, colonIndex)
            val password = auth.substring(colonIndex + 1)
            // The key travels in an Authorization header, so refuse anything but
            // https here rather than failing later with a confusing transport error.
            val baseURL = "https://$host".toHttpUrlOrNull()
            if (!scheme.equals("https", ignoreCase = true) || host.isEmpty() || username.isEmpty() || password.isEmpty() || baseURL == null) {
                throw SimpleFINError.InvalidAccessKey
            }
            return SimpleFINAccessKey(trimmed, baseURL, username, password)
        }

        /** The claim URL a setup token encodes: the base64 of a plain URL — nothing more. */
        fun claimURL(setupToken: String): HttpUrl {
            val trimmed = setupToken.trim().filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
            val decoded = runCatching { Base64.getDecoder().decode(trimmed).toString(Charsets.UTF_8).trim() }.getOrNull()
            val url = decoded?.toHttpUrlOrNull()
            if (url == null || url.scheme != "https") throw SimpleFINError.InvalidSetupToken
            return url
        }
    }
}

/**
 * A `GET /accounts` response. `errors` carries bridge-level problems (a
 * connection that needs re-authenticating, say) alongside whatever account
 * data did come back, so it is never a reason to discard the accounts.
 */
data class SimpleFINAccountSet(val errors: List<String>, val accounts: List<SimpleFINAccount>) {
    companion object {
        fun from(json: JsonObject): SimpleFINAccountSet {
            // Bridges have been seen sending structured errors here rather than
            // the protocol's array of strings; an unreadable `errors` must not
            // cost us the account data alongside it.
            val errors = (json["errors"] as? JsonArray)?.let { array ->
                val strings = array.mapNotNull { it.stringOrNull }
                if (strings.size == array.size) strings else emptyList()
            }.orEmpty()
            val accounts = (json["accounts"] as? JsonArray)?.map {
                SimpleFINAccount.from(it as? JsonObject ?: throw SimpleFINError.InvalidResponse)
            }.orEmpty()
            return SimpleFINAccountSet(errors, accounts)
        }
    }
}

data class SimpleFINOrg(val domain: String?, val name: String?, val id: String?) {
    /**
     * The stable identifier Actual keys its `banks` rows on (`orgDomain ??
     * orgId` upstream), so an account linked here and one linked in the web
     * UI land on the same bank row.
     */
    val bankId: String? get() = domain ?: id

    /** What to show a person picking accounts. */
    val displayName: String get() = name ?: domain ?: id ?: "Unknown institution"
}

data class SimpleFINAccount(
    val id: String,
    val name: String,
    val org: SimpleFINOrg,
    /** Decimal string, e.g. "-1234.56". Use [balanceCents]. */
    val balance: String,
    val availableBalance: String?,
    /** Seconds since the epoch. */
    val balanceDate: Long?,
    val transactions: List<SimpleFINTransaction>,
) {
    val balanceCents: Long? get() = SimpleFINAmount.cents(balance)

    val remoteAccount: io.github.traushb.actuali.services.banksync.BankSyncRemoteAccount
        get() = io.github.traushb.actuali.services.banksync.BankSyncRemoteAccount(
            id = id,
            name = name,
            institutionId = org.bankId ?: org.displayName,
            institutionName = org.displayName,
            balanceCents = balanceCents,
            source = io.github.traushb.actuali.services.banksync.BankSyncSource.SimpleFin,
        )

    companion object {
        fun from(json: JsonObject): SimpleFINAccount {
            val org = json["org"] as? JsonObject ?: throw SimpleFINError.InvalidResponse
            return SimpleFINAccount(
                id = json["id"].stringOrNull ?: throw SimpleFINError.InvalidResponse,
                name = json["name"].stringOrNull ?: "Account",
                org = SimpleFINOrg(org["domain"].stringOrNull, org["name"].stringOrNull, org["id"].stringOrNull),
                balance = json["balance"].stringOrNull ?: "0",
                availableBalance = json["available-balance"].stringOrNull,
                balanceDate = json["balance-date"].longOrNullValue,
                // Absent entirely on a `balances-only` fetch.
                transactions = (json["transactions"] as? JsonArray)?.map {
                    SimpleFINTransaction.from(it as? JsonObject ?: throw SimpleFINError.InvalidResponse)
                }.orEmpty(),
            )
        }
    }
}

data class SimpleFINTransaction(
    val id: String,
    /** Seconds since the epoch; 0 while the transaction is still pending. */
    val posted: Long,
    /** Seconds since the epoch, when the bank reports it. */
    val transactedAt: Long?,
    /** Decimal string, e.g. "-33.45". Use [amountCents]. */
    val amount: String,
    val description: String?,
    val payee: String?,
    val memo: String?,
    val pending: Boolean?,
) {
    val amountCents: Long? get() = SimpleFINAmount.cents(amount)

    /**
     * Whether the bank considers the transaction final. Mirrors upstream's
     * `trans.pending ?? trans.posted === 0`. Booked transactions import as cleared.
     */
    val isBooked: Boolean get() = !(pending ?: (posted == 0L))

    /** Posted date once final, transacted date while still pending. */
    val effectiveTimestamp: Long get() = if (isBooked) posted else (transactedAt ?: posted)

    companion object {
        fun from(json: JsonObject) = SimpleFINTransaction(
            id = json["id"].stringOrNull ?: throw SimpleFINError.InvalidResponse,
            posted = json["posted"].longOrNullValue ?: 0,
            transactedAt = json["transacted_at"].longOrNullValue,
            amount = json["amount"].stringOrNull ?: "0",
            description = json["description"].stringOrNull,
            payee = json["payee"].stringOrNull,
            memo = json["memo"].stringOrNull,
            pending = json["pending"].boolOrNull,
        )
    }
}

/** SimpleFIN sends money as decimal strings ("-33.45") and times as UNIX seconds. */
object SimpleFINAmount {
    /**
     * Integer cents for a SimpleFIN decimal string, or null if it isn't one.
     * Parsed as a decimal so amounts round-trip exactly, with an explicit
     * shape check first.
     */
    fun cents(raw: String): Long? {
        val trimmed = raw.trim()
        if (!isDecimalString(trimmed)) return null
        val value = runCatching { BigDecimal(trimmed.removePrefix("+")) }.getOrNull() ?: return null
        // HALF_UP rounds halves away from zero, matching Transaction.cents.
        return runCatching { value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
    }

    /**
     * `YYYYMMDD` for a UNIX timestamp, in UTC — upstream's server takes the
     * date off an ISO string, so a transaction imported here and by the web
     * UI land on the same day.
     */
    fun day(timestamp: Long): Int {
        val date = Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate()
        return date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
    }

    /** The UNIX timestamp for midnight UTC on a `YYYYMMDD` day — what `start-date` takes. */
    fun timestamp(day: Int): Long = runCatching {
        LocalDate.of(day / 10000, (day % 10000) / 100, day % 100).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    }.getOrDefault(0)

    private fun isDecimalString(value: String): Boolean {
        var digitsSeen = false
        var dotSeen = false
        for ((offset, character) in value.withIndex()) {
            when (character) {
                '+', '-' -> if (offset != 0) return false
                '.' -> if (dotSeen) return false else dotSeen = true
                in '0'..'9' -> digitsSeen = true
                else -> return false
            }
        }
        return digitsSeen
    }
}

/**
 * Talks the SimpleFIN protocol (https://www.simplefin.org/protocol.html)
 * straight from the device: claim a setup token once, then `GET /accounts`
 * with the resulting access key on every sync. Both this and Actual's own
 * server-side SimpleFIN write the bridge's transaction id into
 * `financial_id`, so whichever imports a transaction first, the other
 * recognises it as already imported.
 */
class SimpleFINClient(client: OkHttpClient? = null) {
    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        // A first sync can pull 90 days across every linked account.
        .callTimeout(300, TimeUnit.SECONDS)
        .build()

    /** Exchange a setup token for the long-lived access key. Setup tokens are single-use. */
    suspend fun claimAccessKey(setupToken: String): SimpleFINAccessKey {
        val claimURL = SimpleFINAccessKey.claimURL(setupToken)
        val result = send(Request.Builder().url(claimURL).post(ByteArray(0).toRequestBody()).build())
        if (result.statusCode != 200) {
            logger.error("SimpleFIN claim failed (HTTP ${result.statusCode})")
            throw if (result.statusCode == 403) SimpleFINError.ClaimRejected else SimpleFINError.HttpError(result.statusCode)
        }
        val body = result.text
        // The bridge answers 200 with a "Forbidden…" body for an already-claimed
        // token, so the status code alone doesn't settle it.
        if (body.trim().startsWith("Forbidden")) throw SimpleFINError.ClaimRejected
        return try {
            SimpleFINAccessKey.parse(body)
        } catch (_: SimpleFINError) {
            // A 200 with something that isn't an access key means the token was no good.
            throw SimpleFINError.ClaimRejected
        }
    }

    /**
     * Balances (and optionally transactions) for the accounts the access key covers.
     * @param accountIds restricts the fetch to these bridge account ids; empty fetches everything.
     * @param startDate earliest day (`YYYYMMDD`) to pull transactions from; null asks for balances only.
     */
    suspend fun fetchAccounts(accessKey: SimpleFINAccessKey, accountIds: List<String> = emptyList(), startDate: Int? = null): SimpleFINAccountSet {
        val url = accessKey.baseURL.newBuilder().addPathSegment("accounts").apply {
            if (startDate != null) {
                addQueryParameter("start-date", SimpleFINAmount.timestamp(startDate).toString())
                // Pending transactions are how a purchase shows up before it posts.
                addQueryParameter("pending", "1")
            } else {
                addQueryParameter("balances-only", "1")
            }
            accountIds.forEach { addQueryParameter("account", it) }
        }.build()
        val request = Request.Builder().url(url).get()
            .header("Authorization", accessKey.basicAuthHeader)
            .header("Accept", "application/json")
            .build()
        val result = send(request)
        if (result.statusCode == 401 || result.statusCode == 403) throw SimpleFINError.Forbidden
        if (result.statusCode != 200) throw SimpleFINError.HttpError(result.statusCode)
        return try {
            SimpleFINAccountSet.from(AppJson.parseToJsonElement(result.text) as JsonObject)
        } catch (e: Exception) {
            logger.error("Couldn't decode the SimpleFIN account set: ${e.message}")
            throw SimpleFINError.InvalidResponse
        }
    }

    private suspend fun send(request: Request): HttpResult = try {
        http.newCall(request).await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw SimpleFINError.NetworkError(e.message ?: e.toString())
    }
}

/**
 * Where the claimed SimpleFIN access key lives: secret storage, since it's a
 * bearer credential for the person's bank data. One key per device, not per
 * budget file — a setup token can only be claimed once.
 */
object SimpleFINCredentials {
    private const val ACCESS_KEY_ITEM = "simplefin-access-key"

    /** The stored access key, or null when SimpleFIN isn't set up (or what's stored is unreadable). */
    val accessKey: SimpleFINAccessKey?
        get() {
            val raw = io.github.traushb.actuali.services.Keychain.get(ACCESS_KEY_ITEM) ?: return null
            return try {
                SimpleFINAccessKey.parse(raw)
            } catch (_: SimpleFINError) {
                logger.error("Stored SimpleFIN access key is unreadable")
                null
            }
        }

    val isConfigured: Boolean get() = io.github.traushb.actuali.services.Keychain.get(ACCESS_KEY_ITEM) != null

    fun save(accessKey: SimpleFINAccessKey) = io.github.traushb.actuali.services.Keychain.set(accessKey.raw, ACCESS_KEY_ITEM)

    fun clear() = io.github.traushb.actuali.services.Keychain.remove(ACCESS_KEY_ITEM)
}
