package io.github.traushb.actuali.services.sync

/** Generates CRDT messages for model changes. */
class MessageGenerator(private val clock: HybridLogicalClock) {
    /** Messages for inserting a new object: one per syncable field. */
    suspend fun messagesForInsert(obj: CRDTSyncable): List<CRDTMessage> =
        obj.syncableFields.map { (column, value) ->
            CRDTMessage(clock.send(), obj.datasetName, obj.id, column, CRDTValue.serialize(value))
        }

    /** Messages for updating specific fields. */
    suspend fun messagesForUpdate(obj: CRDTSyncable, changedFields: Set<String>): List<CRDTMessage> {
        val allFields = obj.syncableFields
        return changedFields.filter { it in allFields }.map { column ->
            CRDTMessage(clock.send(), obj.datasetName, obj.id, column, CRDTValue.serialize(allFields[column]))
        }
    }

    /**
     * Messages for raw (dataset, row, column) writes — for tables like
     * zero_budgets/reflect_budgets whose dataset is chosen at runtime.
     */
    suspend fun messages(dataset: String, row: String, fields: List<Pair<String, Any?>>): List<CRDTMessage> =
        fields.map { (column, value) ->
            CRDTMessage(clock.send(), dataset, row, column, CRDTValue.serialize(value))
        }

    /** A tombstone message (soft delete). */
    suspend fun messageForDelete(obj: CRDTSyncable): CRDTMessage =
        CRDTMessage(clock.send(), obj.datasetName, obj.id, "tombstone", CRDTValue.serialize(1))
}
