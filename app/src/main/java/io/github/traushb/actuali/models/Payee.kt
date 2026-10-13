package io.github.traushb.actuali.models

import io.github.traushb.actuali.services.sync.CRDTSyncable

data class Payee(
    override val id: String,
    val name: String,
    /** If this payee represents a transfer to another account. */
    val transferAccountId: String?,
    val tombstone: Boolean = false,
) : CRDTSyncable {
    override val datasetName: String get() = DATASET_NAME

    override val syncableFields: Map<String, Any?>
        get() = linkedMapOf(
            "name" to name,
            "transfer_acct" to transferAccountId,
            "tombstone" to if (tombstone) 1 else 0,
        )

    companion object {
        const val DATASET_NAME = "payees"
    }
}

data class PayeeMapping(
    override val id: String,
    val targetId: String,
) : CRDTSyncable {
    override val datasetName: String get() = DATASET_NAME

    override val syncableFields: Map<String, Any?> get() = linkedMapOf("targetId" to targetId)

    companion object {
        const val DATASET_NAME = "payee_mapping"
    }
}
