package io.github.traushb.actuali.services.banksync

import io.github.traushb.actuali.services.network.ServerBankSyncTransaction
import io.github.traushb.actuali.services.network.SimpleFINAmount
import io.github.traushb.actuali.services.network.SimpleFINTransaction
import io.github.traushb.actuali.services.schedules.DayDate
import kotlin.math.abs

/**
 * One downloaded transaction, normalized into the budget's own units and
 * ready to be matched against what's already in the account.
 */
data class BankSyncCandidate(
    /**
     * The provider's transaction id — stored as `financial_id` and the
     * highest-fidelity way to recognise a transaction we already imported.
     */
    val importedId: String,
    /** YYYYMMDD. */
    val date: Int,
    /** Cents, negative = outflow. */
    val amount: Long,
    val payeeName: String,
    /**
     * The existing payee [payeeName] resolves to, or null when the budget has
     * no payee by that name yet. Resolved before matching because the payee
     * pass compares ids, not names.
     */
    val payeeId: String? = null,
    val notes: String? = null,
    /**
     * Booked at the bank. Pending transactions import uncleared and clear on
     * a later sync, once the bank posts them.
     */
    val cleared: Boolean,
) {
    companion object {
        /**
         * The first name with anything in it. Not every bridge fills in a
         * payee, and importing a nameless one would be worse than reusing the
         * description.
         */
        fun payeeName(candidates: List<String?>): String =
            candidates.firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) } ?: "Unknown"

        /**
         * Escape `#` the way upstream does, so a bank's own "#" in a
         * description doesn't silently become a tag (a doubled `#` never
         * matches as a tag).
         */
        fun notes(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }?.replace("#", "##")

        /**
         * A SimpleFIN transaction as a candidate, or null when it carries no
         * readable amount (nothing sensible to import). Mirrors upstream's
         * `normalizeBankSyncTransactions` for the fields SimpleFIN provides:
         * the bridge's `payee` is the payee, its `description` the notes, and
         * its `id` the dedup key. `payeeId` is left null — the caller resolves it.
         */
        fun fromSimpleFIN(transaction: SimpleFINTransaction): BankSyncCandidate? {
            val amount = transaction.amountCents ?: return null
            return BankSyncCandidate(
                importedId = transaction.id,
                date = SimpleFINAmount.day(transaction.effectiveTimestamp),
                amount = amount,
                payeeName = payeeName(listOf(transaction.payee, transaction.description, transaction.memo)),
                notes = notes(transaction.description),
                cleared = transaction.isBooked,
            )
        }

        /**
         * The same, from the shape the Actual server's `/simplefin/transactions`
         * route returns — already renamed and date-formatted by the time it
         * reaches us, so the raw bridge fields aren't available here.
         */
        fun fromServerBankSync(transaction: ServerBankSyncTransaction): BankSyncCandidate? {
            val importedId = transaction.transactionId ?: return null
            val date = transaction.date?.let(DayDate::fromIso)?.yyyymmdd ?: return null
            val amount = transaction.amount?.let(SimpleFINAmount::cents) ?: return null
            return BankSyncCandidate(
                importedId = importedId,
                date = date,
                amount = amount,
                payeeName = payeeName(listOf(transaction.payeeName, transaction.notes)),
                notes = notes(transaction.notes),
                // The route only omits `booked` for shapes it never sends; treat
                // a missing value as posted rather than importing everything uncleared.
                cleared = transaction.booked ?: true,
            )
        }
    }
}

/** An existing transaction in the fuzzy-match window, projected to the columns matching reads. */
data class BankSyncExistingTransaction(
    val id: String,
    /** YYYYMMDD. */
    val date: Int,
    /** Cents. */
    val amount: Long,
    val payeeId: String?,
    val importedId: String?,
    val importedPayee: String?,
    val notes: String?,
    val cleared: Boolean,
    val reconciled: Boolean,
    val tombstone: Boolean = false,
)

/** The columns a matched transaction takes from the downloaded one. */
data class BankSyncUpdate(
    val expected: BankSyncExistingTransaction,
    val existingId: String,
    val importedId: String,
    val payeeId: String?,
    val importedPayee: String?,
    val notes: String?,
    val cleared: Boolean,
)

data class BankSyncPlan(
    val inserts: List<BankSyncCandidate> = emptyList(),
    val updates: List<BankSyncUpdate> = emptyList(),
    /** Downloaded transactions that matched something already correct. */
    val unchanged: Int = 0,
    /** Conflicting downloads with the same provider id are not imported. */
    val rejectedConflicts: Int = 0,
) {
    val isEmpty: Boolean get() = inserts.isEmpty() && updates.isEmpty()
}

/**
 * Decides which downloaded transactions are new, which ones are transactions
 * the budget already has, and what the latter should take from them.
 *
 * Port of upstream loot-core `matchTransactions` / `reconcileTransactions`
 * (`server/accounts/sync.ts`) for the bank-sync case, so a transaction
 * entered by hand before it reached the bank feed is updated rather than
 * duplicated. Three passes, highest fidelity first: the provider's own
 * transaction id, then same-payee, then anything left in the window.
 *
 * Pure and synchronous — the caller does the payee lookups and the writing.
 */
object BankSyncReconciler {
    /** How far either side of a downloaded transaction's date to look, matching upstream's window. */
    const val FUZZY_MATCH_DAY_RADIUS = 7

    private class FuzzyMatch(val row: BankSyncExistingTransaction, val distance: Int)

    private class Pending(val candidate: BankSyncCandidate, var match: BankSyncExistingTransaction?, val window: List<FuzzyMatch>)

    fun plan(
        candidates: List<BankSyncCandidate>,
        existing: List<BankSyncExistingTransaction>,
        reimportDeleted: Boolean = true,
    ): BankSyncPlan {
        val matchedIds = HashSet<String>()
        var rejectedConflicts = 0
        val normalizedCandidates = normalize(candidates) { rejectedConflicts += 1 }
        val sortedExisting = existing.sortedBy { it.id }

        // Pass 1: the provider's transaction id. Anything that misses gets a
        // window of same-amount transactions to try the later passes against,
        // nearest date first. Unlike a file import, the window keeps rows that
        // already carry a *different* imported id (upstream's
        // `strictIdChecking: false` for bank-sync accounts): providers hand
        // out a new id for the same transaction often enough — a pending
        // charge that posts, most commonly — that excluding them would
        // duplicate it.
        val pending = mutableListOf<Pending>()
        for (candidate in normalizedCandidates) {
            val liveMatch = sortedExisting.firstOrNull {
                it.importedId == candidate.importedId && !it.tombstone && it.id !in matchedIds
            }
            val deletedMatch = if (reimportDeleted) null else sortedExisting.firstOrNull {
                it.importedId == candidate.importedId && it.tombstone
            }
            val match = liveMatch ?: deletedMatch
            if (match != null) {
                matchedIds += match.id
                pending += Pending(candidate, match, emptyList())
                continue
            }
            val window = existing.mapNotNull { row ->
                if (row.tombstone || row.amount != candidate.amount) return@mapNotNull null
                val distance = dayDistance(row.date, candidate.date) ?: return@mapNotNull null
                if (distance <= FUZZY_MATCH_DAY_RADIUS) FuzzyMatch(row, distance) else null
            }
            pending += Pending(candidate, null, window)
        }

        // Pass 2: same payee. Runs across every candidate before pass 3 so a
        // confident match always wins the row a vaguer one would have taken.
        for (entry in pending) {
            if (entry.match != null) continue
            val payeeId = entry.candidate.payeeId ?: continue
            val match = nearestMatch(entry.window, matchedIds, payeeId) ?: continue
            matchedIds += match.id
            entry.match = match
        }

        // Pass 3: whatever is left in the window — same account, same amount, within a week.
        for (entry in pending) {
            if (entry.match != null) continue
            val match = nearestMatch(entry.window, matchedIds) ?: continue
            matchedIds += match.id
            entry.match = match
        }

        val inserts = mutableListOf<BankSyncCandidate>()
        val updates = mutableListOf<BankSyncUpdate>()
        var unchanged = 0
        for (entry in pending) {
            val match = entry.match
            if (match == null) {
                inserts += entry.candidate
                continue
            }
            // Reconciled and deleted transactions are locked; upstream leaves
            // both alone. A deleted row only acts as an exact-ID dedupe key.
            if (match.reconciled || match.tombstone) {
                unchanged += 1
                continue
            }
            val update = update(entry.candidate, match)
            if (changed(update, match)) updates += update else unchanged += 1
        }
        return BankSyncPlan(inserts, updates, unchanged, rejectedConflicts)
    }

    private fun nearestMatch(window: List<FuzzyMatch>, excluding: Set<String>, payeeId: String? = null): BankSyncExistingTransaction? =
        window.asSequence()
            .filter { it.row.id !in excluding && (payeeId == null || it.row.payeeId == payeeId) }
            .minWithOrNull(compareBy<FuzzyMatch> { it.distance }.thenBy { it.row.id })
            ?.row

    /**
     * Identical retries are harmless. Conflicting payloads for one provider id
     * are rejected instead of selecting a winner from input order.
     */
    private fun normalize(candidates: List<BankSyncCandidate>, onConflict: () -> Unit): List<BankSyncCandidate> {
        val grouped = candidates.groupBy { it.importedId }
        val normalized = mutableListOf<BankSyncCandidate>()
        for (importedId in grouped.keys.sorted()) {
            val variants = grouped.getValue(importedId)
            val first = variants.first()
            if (!variants.drop(1).all { it == first }) {
                onConflict()
                continue
            }
            normalized += variants
        }
        return normalized
    }

    /**
     * What a matched transaction ends up with. Anything the person already
     * filled in wins — the download only fills blanks — except the two fields
     * that are the bank's to state: its transaction id and whether it posted.
     */
    private fun update(candidate: BankSyncCandidate, existing: BankSyncExistingTransaction) = BankSyncUpdate(
        expected = existing,
        existingId = existing.id,
        importedId = candidate.importedId,
        payeeId = existing.payeeId ?: candidate.payeeId,
        importedPayee = candidate.payeeName,
        notes = existing.notes ?: candidate.notes,
        cleared = existing.cleared || candidate.cleared,
    )

    private fun changed(update: BankSyncUpdate, existing: BankSyncExistingTransaction): Boolean =
        update.importedId != existing.importedId ||
            update.payeeId != existing.payeeId ||
            update.importedPayee != existing.importedPayee ||
            update.notes != existing.notes ||
            update.cleared != existing.cleared

    /**
     * Whole days between two `YYYYMMDD` dates, or null if either isn't a real
     * calendar date. Timezone-free, so the match window is the same width
     * wherever the phone is.
     */
    fun dayDistance(a: Int, b: Int): Int? {
        val from = DayDate.fromYYYYMMDD(a) ?: return null
        val to = DayDate.fromYYYYMMDD(b) ?: return null
        return abs(from.days(to))
    }
}
