package io.github.traushb.actuali.models

import io.github.traushb.actuali.services.sync.CRDTSyncable

data class Account(
    override val id: String,
    val name: String,
    val type: AccountType,
    val offBudget: Boolean,
    val closed: Boolean,
    val sortOrder: Long,
    /** Stored in cents. */
    val balance: Long,
) : CRDTSyncable {
    override val datasetName: String get() = DATASET_NAME

    /**
     * Only the fields a manually-created (non-bank-linked) account sets.
     * Bank-sync columns (account_id, balance_current, mask, official_name,
     * subtype, bank) stay untouched — matching how the PWA leaves them null
     * for a local account, same as `financial_id` on Transaction.
     */
    override val syncableFields: Map<String, Any?>
        get() = linkedMapOf(
            "name" to name,
            "type" to type.rawValue,
            "offbudget" to if (offBudget) 1 else 0,
            "closed" to if (closed) 1 else 0,
            "tombstone" to 0,
            "sort_order" to sortOrder,
        )

    companion object {
        const val DATASET_NAME = "accounts"
    }
}

/**
 * Cleared / uncleared / reconciled totals for one account (GH #134).
 * Reconciled rows are a subset of cleared, so cleared + uncleared is the
 * account balance while reconciled is informational.
 */
data class AccountBalanceBreakdown(
    val cleared: Long,
    val uncleared: Long,
    val reconciled: Long,
)

enum class AccountType(val rawValue: String) {
    Checking("checking"),
    Savings("savings"),
    Credit("credit"),
    Investment("investment"),
    Mortgage("mortgage"),
    Debt("debt"),
    Other("other"),
    ;

    companion object {
        fun fromRaw(raw: String?): AccountType? = entries.firstOrNull { it.rawValue == raw }
    }
}
