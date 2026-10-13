package io.github.traushb.actuali.models

import io.github.traushb.actuali.services.sync.CRDTSyncable

/**
 * A recorded GPS coordinate for a payee (upstream `payee_locations` table,
 * Actual >= 26.4.0). `createdAt` is milliseconds since epoch, matching
 * upstream's `Date.now()`.
 */
data class PayeeLocation(
    override val id: String,
    val payeeId: String,
    val latitude: Double,
    val longitude: Double,
    val createdAt: Long,
    val tombstone: Boolean = false,
) : CRDTSyncable {
    override val datasetName: String get() = DATASET_NAME

    override val syncableFields: Map<String, Any?>
        get() = linkedMapOf(
            "payee_id" to payeeId,
            "latitude" to latitude,
            "longitude" to longitude,
            "created_at" to createdAt,
            "tombstone" to if (tombstone) 1 else 0,
        )

    companion object {
        const val DATASET_NAME = "payee_locations"
    }
}

/** A payee paired with how many locations it currently has recorded. */
data class PayeeLocationSummary(val payee: Payee, val locationCount: Int) {
    val id: String get() = payee.id
}

/** A payee paired with its closest recorded location relative to the query point. */
data class NearbyPayee(val payee: Payee, val location: PayeeLocation, val distanceMeters: Double) {
    val id: String get() = payee.id
}
