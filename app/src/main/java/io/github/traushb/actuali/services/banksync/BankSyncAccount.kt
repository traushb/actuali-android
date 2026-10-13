package io.github.traushb.actuali.services.banksync

import io.github.traushb.actuali.services.sync.CRDTSyncable

/** The provider an account's transactions are downloaded from (`accounts.account_sync_source`). */
enum class BankSyncSource(val rawValue: String) {
    SimpleFin("simpleFin"),

    /**
     * Apple Wallet links made by the iOS app. They stay device-local there,
     * but the value can still appear in synced data, so it's recognised.
     */
    FinanceKit("financeKit"),
    ;

    companion object {
        fun fromRaw(raw: String?): BankSyncSource? = entries.firstOrNull { it.rawValue == raw }
    }
}

data class ExpectedBankSyncLink(val accountId: String, val externalAccountId: String, val source: String)

/**
 * An account wired up to a bank feed: the budget's account plus the
 * provider-side id its transactions come from.
 */
data class BankSyncAccount(
    /** The budget's account id. */
    val id: String,
    val name: String,
    /** The id the provider knows the account by (`accounts.account_id`). */
    val externalAccountId: String,
    /** The provider name, preserved verbatim from `accounts.account_sync_source`. */
    val syncSource: String,
    val offBudget: Boolean,
    val closed: Boolean,
) {
    val source: BankSyncSource? get() = BankSyncSource.fromRaw(syncSource)
}

/** A provider-side account offered for linking — what the link flow needs and nothing more. */
data class BankSyncRemoteAccount(
    /** The provider's account id. */
    val id: String,
    val name: String,
    /** The provider's institution id. */
    val institutionId: String,
    val institutionName: String,
    val balanceCents: Long?,
    val source: BankSyncSource,
)

/**
 * Actual's `banks` row: the institution behind one or more linked accounts.
 * Written so an account linked here looks the same to the web UI as one
 * linked there — its unlink path reads `accounts.bank` and does nothing
 * without it.
 */
data class Bank(
    override val id: String,
    /** The provider's own institution id — SimpleFIN's org domain, falling back to its org id. */
    val bankId: String,
    val name: String,
    val tombstone: Boolean = false,
) : CRDTSyncable {
    override val datasetName: String get() = DATASET_NAME

    override val syncableFields: Map<String, Any?>
        get() = linkedMapOf(
            "bank_id" to bankId,
            "name" to name,
            "tombstone" to if (tombstone) 1 else 0,
        )

    companion object {
        const val DATASET_NAME = "banks"
    }
}
