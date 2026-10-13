package io.github.traushb.actuali.services.banksync

import io.github.traushb.actuali.services.network.ActualServerClient
import io.github.traushb.actuali.services.network.SimpleFINAccessKey
import io.github.traushb.actuali.services.network.SimpleFINAccount
import io.github.traushb.actuali.services.network.SimpleFINClient
import io.github.traushb.actuali.services.schedules.DayDate

/** One account to download, and how far back to reach for it. */
data class BankSyncTarget(
    /** The provider's account id (`accounts.account_id`). */
    val externalId: String,
    /** Earliest day (`YYYYMMDD`) worth downloading for this account. */
    val startDay: Int,
)

/** What a provider came back with for one account. */
data class BankSyncDownload(
    val candidates: List<BankSyncCandidate> = emptyList(),
    /** The account's balance as of now, for working out an opening balance on a first sync. */
    val currentBalanceCents: Long? = null,
    /** Something the person should know about this account, if anything. */
    val problem: String? = null,
    /** Actual's `bank_sync_status` value for this account after the download, so the web UI reads the same state this device saw. */
    val status: String = "ok",
    /** Whether the provider returned the account payload, even with no transactions. An error-only response leaves this false. */
    val accountDataReceived: Boolean = false,
)

data class BankSyncDownloadSet(
    val byAccount: Map<String, BankSyncDownload> = emptyMap(),
    /** Problems that aren't tied to a single account. */
    val problems: List<String> = emptyList(),
)

/**
 * Where a bank download comes from.
 *
 * Two of these exist because the credential can live in either of two places
 * and they can't be shared: Actual keeps the server's SimpleFIN access key in
 * its own secrets store, deliberately out of the budget file, so no amount of
 * syncing will hand it to this device. Preferring the server when it has one
 * (see `BudgetStore.makeBankSyncProvider`) means a person who set SimpleFIN up
 * on the web doesn't have to claim a second setup token here, and a person who
 * links an account here doesn't leave the web with a link it can't service.
 * The device's own key is the fallback for servers without SimpleFIN.
 */
interface BankSyncProvider {
    /** Every account the connection covers, for the linking screen. */
    suspend fun accounts(): List<SimpleFINAccount>

    suspend fun download(targets: List<BankSyncTarget>): BankSyncDownloadSet
}

/** Talks to the SimpleFIN bridge directly with a key this device claimed. */
class SimpleFINDirectProvider(private val client: SimpleFINClient, private val accessKey: SimpleFINAccessKey) : BankSyncProvider {
    override suspend fun accounts(): List<SimpleFINAccount> = client.fetchAccounts(accessKey).accounts

    override suspend fun download(targets: List<BankSyncTarget>): BankSyncDownloadSet {
        val earliest = targets.minOfOrNull { it.startDay } ?: return BankSyncDownloadSet()

        // One request covers every account, so it reaches back as far as the
        // hungriest of them; each account drops the surplus below.
        val downloaded = client.fetchAccounts(accessKey, targets.map { it.externalId }, earliest)

        val byAccount = LinkedHashMap<String, BankSyncDownload>()
        for (target in targets) {
            val remote = downloaded.accounts.firstOrNull { it.id == target.externalId } ?: continue
            // The bridge's errors aren't keyed by account, so pair them up the
            // way upstream's server does — by the institution they name.
            val attention = downloaded.errors.firstOrNull { it.startsWith("Connection to ${remote.org.displayName} may need attention") }
            byAccount[target.externalId] = BankSyncDownload(
                candidates = remote.transactions.mapNotNull(BankSyncCandidate::fromSimpleFIN).filter { it.date >= target.startDay },
                currentBalanceCents = remote.balanceCents,
                problem = attention,
                status = if (attention != null) "attention-required" else "ok",
                accountDataReceived = true,
            )
        }

        // Anything left over is about the connection, not one account.
        val named = downloaded.accounts
            .filter { account -> targets.any { it.externalId == account.id } }
            .map { "Connection to ${it.org.displayName} may need attention" }
            .toSet()
        return BankSyncDownloadSet(byAccount, downloaded.errors.filter { error -> named.none { error.startsWith(it) } })
    }
}

/**
 * Routes the download through the Actual server's own SimpleFIN credentials,
 * which it already exposes to any authenticated client at `/simplefin/…`.
 */
class ActualServerBankSyncProvider(private val client: ActualServerClient) : BankSyncProvider {
    override suspend fun accounts(): List<SimpleFINAccount> = client.simpleFINAccounts()

    override suspend fun download(targets: List<BankSyncTarget>): BankSyncDownloadSet {
        if (targets.isEmpty()) return BankSyncDownloadSet()

        val downloaded = client.simpleFINTransactions(
            targets.map { it.externalId },
            targets.map { DayDate.fromYYYYMMDD(it.startDay)?.iso ?: "" },
        )
        // The whole request was rejected, so no account got as far as having its own problem.
        downloaded.failure?.let { return BankSyncDownloadSet(problems = listOf(it.reason ?: "Your server's SimpleFIN connection was rejected.")) }

        val byAccount = LinkedHashMap<String, BankSyncDownload>()
        for (target in targets) {
            val account = downloaded.accounts[target.externalId]
            val error = downloaded.errors[target.externalId]?.firstOrNull()
            // Neither data nor an error means the server didn't mention this
            // account at all; leave the key out so the caller says so.
            if (account == null && error == null) continue
            byAccount[target.externalId] = BankSyncDownload(
                candidates = account?.transactions.orEmpty().mapNotNull(BankSyncCandidate::fromServerBankSync).filter { it.date >= target.startDay },
                currentBalanceCents = account?.startingBalance,
                problem = error?.let { it.reason ?: it.errorCode },
                status = error?.bankSyncStatus ?: "ok",
                accountDataReceived = account != null,
            )
        }
        return BankSyncDownloadSet(byAccount)
    }
}
