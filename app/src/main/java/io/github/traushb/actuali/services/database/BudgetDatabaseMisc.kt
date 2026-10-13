package io.github.traushb.actuali.services.database

import io.github.traushb.actuali.models.CreditCardConfig
import io.github.traushb.actuali.models.DepositConfig
import io.github.traushb.actuali.models.LoanConfig
import io.github.traushb.actuali.models.NearbyPayee
import io.github.traushb.actuali.models.Payee
import io.github.traushb.actuali.models.PayeeLocation
import io.github.traushb.actuali.models.PayeeLocationSummary
import io.github.traushb.actuali.models.Tag
import io.github.traushb.actuali.models.TagSummary
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.AppJson
import io.github.traushb.actuali.services.database.BudgetDatabase.Companion.TRANSACTION_SELECT
import io.github.traushb.actuali.services.database.BudgetDatabase.Companion.mapTransaction
import io.github.traushb.actuali.services.location.LocationUtils
import io.github.traushb.actuali.services.rules.TagFilter
import kotlinx.serialization.KSerializer

// Preferences (synced budget-level key-value store)

/** Preference key prefixes for synced per-account configurations and card mappings. */
object PreferenceKeys {
    const val CREDIT_CARD_PREFIX = "actuali:credit_card:"
    const val LOAN_PREFIX = "actuali:loan:"
    const val DEPOSIT_PREFIX = "actuali:deposit:"
    const val CARD_MAPPING_PREFIX = "actuali:card_mapping:"

    fun creditCard(accountId: String) = "$CREDIT_CARD_PREFIX$accountId"

    fun loan(accountId: String) = "$LOAN_PREFIX$accountId"

    fun deposit(accountId: String) = "$DEPOSIT_PREFIX$accountId"

    fun cardMapping(keyword: String) = "$CARD_MAPPING_PREFIX$keyword"
}

/**
 * Decodes every `preferences` row stored under [prefix], keyed by the account
 * id the key ends with. A row that no longer decodes is skipped rather than
 * failing the load, so one bad value can't cost the others.
 */
private suspend fun <T> BudgetDatabase.fetchAccountConfigs(prefix: String, serializer: KSerializer<T>): Map<String, T> = dbQueue.readAsync { db ->
    if (!db.tableExists("preferences")) return@readAsync emptyMap()
    db.fetchAll("SELECT id, value FROM preferences WHERE id LIKE ? AND value IS NOT NULL", "$prefix%").mapNotNull { row ->
        val id = row.string("id")?.takeIf { it.startsWith(prefix) } ?: return@mapNotNull null
        val value = row.string("value") ?: return@mapNotNull null
        val config = runCatching { AppJson.decodeFromString(serializer, value) }.getOrNull() ?: return@mapNotNull null
        id.removePrefix(prefix) to config
    }.toMap()
}

suspend fun BudgetDatabase.fetchCreditCardConfigs(): Map<String, CreditCardConfig> =
    fetchAccountConfigs(PreferenceKeys.CREDIT_CARD_PREFIX, CreditCardConfig.serializer())

suspend fun BudgetDatabase.fetchLoanConfigs(): Map<String, LoanConfig> = fetchAccountConfigs(PreferenceKeys.LOAN_PREFIX, LoanConfig.serializer())

suspend fun BudgetDatabase.fetchDepositConfigs(): Map<String, DepositConfig> =
    fetchAccountConfigs(PreferenceKeys.DEPOSIT_PREFIX, DepositConfig.serializer())

/**
 * Everything ever paid into a loan account, in cents. On a loan the money
 * only moves one way: payments arrive as inflows while the opening balance and
 * the lender's charges are outflows, so summing the inflows is the total
 * paid. Split parents are skipped in favour of their children.
 */
suspend fun BudgetDatabase.totalPaidIntoAccount(accountId: String): Long = dbQueue.readAsync { db ->
    db.long(
        """
        SELECT COALESCE(SUM(amount), 0)
        FROM transactions
        WHERE acct = ?
          AND (tombstone = 0 OR tombstone IS NULL)
          AND (isParent = 0 OR isParent IS NULL)
          AND amount > 0
        """,
        accountId,
    ) ?: 0
}

/** Synced card-to-account mappings stored one per `preferences` row: keyword -> accountId. */
suspend fun BudgetDatabase.fetchCardAccountMappings(): Map<String, String> = dbQueue.readAsync { db ->
    if (!db.tableExists("preferences")) return@readAsync emptyMap()
    val prefix = PreferenceKeys.CARD_MAPPING_PREFIX
    db.fetchAll("SELECT id, value FROM preferences WHERE id LIKE ? AND value IS NOT NULL", "$prefix%").mapNotNull { row ->
        val id = row.string("id")?.takeIf { it.startsWith(prefix) } ?: return@mapNotNull null
        val accountId = row.string("value") ?: return@mapNotNull null
        val keyword = id.removePrefix(prefix).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        keyword to accountId
    }.toMap()
}

/** Currency code from the preferences table (stored by Actual Budget); null when not set. */
suspend fun BudgetDatabase.fetchCurrencyCode(): String? = dbQueue.readAsync { db ->
    if (!db.tableExists("preferences")) return@readAsync null
    db.string("SELECT value FROM preferences WHERE id = 'defaultCurrencyCode'")
}

/** Budget-wide upcoming-schedule window, as stored by Actual; null when unset. */
suspend fun BudgetDatabase.fetchUpcomingScheduledTransactionLength(): String? = dbQueue.readAsync { db ->
    if (!db.tableExists("preferences")) return@readAsync null
    db.string("SELECT value FROM preferences WHERE id = 'upcomingScheduledTransactionLength'")
}

// Payee Insert

fun BudgetDatabase.insertPayee(payee: Payee) = dbQueue.write { db ->
    db.execute(
        "INSERT INTO payees (id, name, transfer_acct, tombstone) VALUES (?, ?, ?, ?)",
        payee.id, payee.name, payee.transferAccountId, if (payee.tombstone) 1 else 0,
    )
    // Also insert into payee_mapping (required for transaction joins)
    db.execute("INSERT INTO payee_mapping (id, targetId) VALUES (?, ?)", payee.id, payee.id)
}

// Payee Locations

fun BudgetDatabase.insertPayeeLocation(location: PayeeLocation) = dbQueue.write { db ->
    db.execute(
        "INSERT INTO payee_locations (id, payee_id, latitude, longitude, created_at, tombstone) VALUES (?, ?, ?, ?, ?, ?)",
        location.id, location.payeeId, location.latitude, location.longitude, location.createdAt, if (location.tombstone) 1 else 0,
    )
}

/** Soft-delete one recorded location (CRDT tombstone, matching upstream). */
fun BudgetDatabase.tombstonePayeeLocation(id: String) = dbQueue.write { db ->
    db.execute("UPDATE payee_locations SET tombstone = 1 WHERE id = ?", id)
}

private fun Row.payeeLocation(idColumn: String = "id"): PayeeLocation = PayeeLocation(
    id = string(idColumn) ?: "",
    payeeId = string("payee_id") ?: "",
    latitude = double("latitude") ?: 0.0,
    longitude = double("longitude") ?: 0.0,
    createdAt = long("created_at") ?: 0,
)

/** Non-tombstoned locations for a payee, newest first (upstream getPayeeLocations ordering). */
suspend fun BudgetDatabase.fetchPayeeLocations(payeeId: String): List<PayeeLocation> = dbQueue.readAsync { db ->
    db.fetchAll(
        """
        SELECT id, payee_id, latitude, longitude, created_at
        FROM payee_locations
        WHERE tombstone IS NOT 1 AND payee_id = ?
          AND latitude IS NOT NULL AND longitude IS NOT NULL AND created_at IS NOT NULL
        ORDER BY created_at DESC
        """,
        payeeId,
    ).map { it.payeeLocation() }
}

/**
 * Every non-tombstoned payee that still has at least one non-tombstoned
 * location, name-ordered, with its live location count — the top level of
 * the Payee Locations screen.
 */
suspend fun BudgetDatabase.fetchPayeesWithLocations(): List<PayeeLocationSummary> = dbQueue.readAsync { db ->
    db.fetchAll(
        """
        SELECT p.id, p.name, p.transfer_acct, COUNT(pl.id) AS location_count
        FROM payees p
        JOIN payee_locations pl ON pl.payee_id = p.id
        WHERE p.tombstone IS NOT 1 AND pl.tombstone IS NOT 1
          AND pl.latitude IS NOT NULL AND pl.longitude IS NOT NULL
          AND pl.created_at IS NOT NULL
        GROUP BY p.id
        ORDER BY p.name COLLATE NOCASE ASC, p.id ASC
        """,
    ).map { row ->
        PayeeLocationSummary(
            Payee(row.string("id") ?: "", row.string("name") ?: "Unknown", row.string("transfer_acct")),
            row.int("location_count") ?: 0,
        )
    }
}

/**
 * Nearby payees: closest non-tombstoned location per non-tombstoned payee
 * within [maxDistanceMeters], ascending by distance, limit 10 (upstream
 * getNearbyPayees). Distance is computed here rather than with SQLite math
 * functions, matching the iOS app.
 */
suspend fun BudgetDatabase.fetchNearbyPayees(
    latitude: Double,
    longitude: Double,
    maxDistanceMeters: Double = LocationUtils.DEFAULT_MAX_DISTANCE_METERS,
): List<NearbyPayee> {
    if (!LocationUtils.isValidCoordinate(latitude, longitude) || !maxDistanceMeters.isFinite() || maxDistanceMeters <= 0) return emptyList()
    val candidates = dbQueue.readAsync { db ->
        db.fetchAll(
            """
            SELECT pl.id AS location_id, pl.payee_id, pl.latitude, pl.longitude, pl.created_at,
                   p.name, p.transfer_acct
            FROM payee_locations pl
            JOIN payees p ON p.id = pl.payee_id
            WHERE pl.tombstone IS NOT 1 AND p.tombstone IS NOT 1
              AND pl.latitude IS NOT NULL AND pl.longitude IS NOT NULL AND pl.created_at IS NOT NULL
            """,
        ).map { row ->
            val location = row.payeeLocation("location_id")
            NearbyPayee(
                payee = Payee(location.payeeId, row.string("name") ?: "Unknown", row.string("transfer_acct")),
                location = location,
                distanceMeters = LocationUtils.calculateDistanceMeters(latitude, longitude, location.latitude, location.longitude),
            )
        }
    }
    val closestByPayee = HashMap<String, NearbyPayee>()
    for (candidate in candidates) {
        if (candidate.distanceMeters > maxDistanceMeters) continue
        val existing = closestByPayee[candidate.payee.id]
        if (existing != null && existing.distanceMeters <= candidate.distanceMeters) continue
        closestByPayee[candidate.payee.id] = candidate
    }
    return closestByPayee.values.sortedWith(compareBy<NearbyPayee> { it.distanceMeters }.thenBy { it.payee.id }).take(10)
}

// Tags

/**
 * Every tag row, tombstoned included. The server's UNIQUE(tags.tag) spans
 * tombstones, so name checks must run against all rows.
 */
suspend fun BudgetDatabase.allTags(): List<Tag> = dbQueue.readAsync { db ->
    if (!db.tableExists("tags")) return@readAsync emptyList()
    val hasHidden = "hidden" in db.columns("tags")
    db.fetchAll(
        """
        SELECT id, tag, color, description, ${if (hasHidden) "hidden" else "0 AS hidden"}, tombstone
        FROM tags
        ORDER BY tag COLLATE NOCASE ASC
        """,
    ).map { row ->
        Tag(
            id = row.string("id") ?: "",
            tag = row.string("tag") ?: "",
            color = row.string("color"),
            description = row.string("description"),
            hidden = (row.long("hidden") ?: 0) != 0L,
            tombstone = (row.long("tombstone") ?: 0) != 0L,
        )
    }
}

suspend fun BudgetDatabase.fetchTags(includeHidden: Boolean = true): List<Tag> =
    allTags().filter { !it.tombstone && (includeHidden || !it.hidden) }

fun BudgetDatabase.insertTag(tag: Tag) = dbQueue.write { db ->
    db.execute(
        "INSERT INTO tags (id, tag, color, description, hidden, tombstone) VALUES (?, ?, ?, ?, ?, ?)",
        tag.id, tag.tag, tag.color, tag.description, if (tag.hidden) 1 else 0, if (tag.tombstone) 1 else 0,
    )
}

fun BudgetDatabase.updateTag(tag: Tag) = dbQueue.write { db ->
    db.execute(
        "UPDATE tags SET tag = ?, color = ?, description = ?, hidden = ?, tombstone = ? WHERE id = ?",
        tag.tag, tag.color, tag.description, if (tag.hidden) 1 else 0, if (tag.tombstone) 1 else 0, tag.id,
    )
}

fun BudgetDatabase.deleteTag(id: String) = dbQueue.write { db -> db.execute("UPDATE tags SET tombstone = 1 WHERE id = ?", id) }

/**
 * Upstream parity: renaming a tag updates the `tags` row AND rewrites all
 * occurrences of `#oldName` to `#newName` in transaction notes. Returns the
 * affected transaction ids and their new notes so callers can generate CRDT
 * messages for both the tag and the modified transactions.
 */
fun BudgetDatabase.renameTag(id: String, oldName: String, newName: String): List<Pair<String, String>> = dbQueue.write { db ->
    db.execute("UPDATE tags SET tag = ? WHERE id = ?", newName, id)
    // Upstream regex: (?<!#)#oldName([\s#]|$)
    val regex = Regex("(?<!#)#${Regex.escape(oldName)}([\\s#]|$)")
    val replacement = Regex.escapeReplacement("#$newName") + "$1"
    val updated = mutableListOf<Pair<String, String>>()
    for (row in db.fetchAll("SELECT id, notes FROM transactions WHERE notes LIKE ? AND (tombstone = 0 OR tombstone IS NULL)", "%#$oldName%")) {
        val txId = row.string("id") ?: continue
        val notes = row.string("notes") ?: continue
        val replaced = regex.replace(notes, replacement)
        if (replaced != notes) {
            db.execute("UPDATE transactions SET notes = ? WHERE id = ?", replaced, txId)
            updated += txId to replaced
        }
    }
    updated
}

/**
 * All unique tag names from transaction notes that no active tag already uses.
 * Tombstoned names stay discoverable — importing them reactivates the old row
 * instead of inserting a duplicate the server's UNIQUE(tags.tag) would reject.
 */
suspend fun BudgetDatabase.discoverTags(): List<String> = dbQueue.readAsync { db ->
    if (!db.tableExists("tags")) return@readAsync emptyList()
    val existingTags = db.strings("SELECT LOWER(tag) FROM tags WHERE tag IS NOT NULL AND (tombstone = 0 OR tombstone IS NULL)").toSet()
    val discovered = HashSet<String>()
    val order = mutableListOf<String>()
    for (note in db.strings("SELECT notes FROM transactions WHERE notes LIKE '%#%' AND (tombstone = 0 OR tombstone IS NULL)")) {
        for (rawTag in TagFilter.extractHashtags(note)) {
            val normalized = Tag.normalizeTagName(rawTag)
            if (!Tag.isValidTagName(normalized)) continue
            val lower = normalized.lowercase()
            if (lower !in existingTags && discovered.add(lower)) order += normalized
        }
    }
    order
}

/** Aggregates transaction count and total spend for all tags. */
suspend fun BudgetDatabase.fetchTagSummaries(): List<TagSummary> {
    val allTags = fetchTags(includeHidden = true)
    if (allTags.isEmpty()) return emptyList()
    return dbQueue.readAsync { db ->
        // ponytail: Scan transactions with notes once in memory to compute all
        // tag aggregations in O(tags * txs_with_notes), extracting note
        // hashtags upfront.
        val tagged = db.fetchAll(
            """
            SELECT id, amount, notes
            FROM transactions
            WHERE notes IS NOT NULL AND notes != ''
              AND (tombstone = 0 OR tombstone IS NULL)
              AND (isChild = 0 OR isChild IS NULL)
            """,
        ).mapNotNull { row ->
            val notes = row.string("notes")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            (row.long("amount") ?: 0) to TagFilter.extractHashtags(notes).map { it.lowercase() }.toSet()
        }
        allTags.map { tag ->
            val needle = "#${tag.tag}".lowercase()
            val matches = tagged.filter { needle in it.second }
            TagSummary(
                tag = tag,
                transactionCount = matches.size,
                totalSpent = matches.sumOf { if (it.first < 0) -it.first else 0L },
                netAmount = matches.sumOf { it.first },
            )
        }
    }
}

/** Transactions carrying the given tag in their notes, newest first. */
suspend fun BudgetDatabase.fetchTransactions(taggedWith: String): List<Transaction> {
    val needle = "#$taggedWith"
    return dbQueue.readAsync { db ->
        db.fetchAll("$TRANSACTION_SELECT AND t.notes LIKE ? ORDER BY t.date DESC, t.sort_order DESC", "%#$taggedWith%")
            .map(::mapTransaction)
            .filter { tx -> tx.notes?.takeIf { it.isNotEmpty() }?.let { TagFilter.notesContainTag(it, needle, caseSensitive = false) } ?: false }
    }
}
