package io.github.traushb.actuali.models

import io.github.traushb.actuali.services.sync.CRDTSyncable

data class Category(
    override val id: String,
    val name: String,
    val groupId: String,
    val isIncome: Boolean,
    val hidden: Boolean,
    val sortOrder: Double,
) : CRDTSyncable {
    override val datasetName: String get() = DATASET_NAME

    /**
     * The columns upstream's `insertCategory` writes. Goal templates
     * (`goal_def`) and note cleanups (`cleanup_def`) stay untouched — the
     * PWA leaves them null on a fresh category too.
     */
    override val syncableFields: Map<String, Any?>
        get() = linkedMapOf(
            "name" to name,
            "cat_group" to groupId,
            "is_income" to if (isIncome) 1 else 0,
            "hidden" to if (hidden) 1 else 0,
            "tombstone" to 0,
            "sort_order" to sortOrder,
        )

    companion object {
        const val DATASET_NAME = "categories"
    }
}

data class CategoryGroup(
    override val id: String,
    val name: String,
    val isIncome: Boolean,
    val hidden: Boolean,
    val sortOrder: Double,
    val categories: List<Category>,
) : CRDTSyncable {
    override val datasetName: String get() = DATASET_NAME

    /**
     * `categories` is the assembled child list, not a column — the rows it
     * holds sync as their own `categories` messages.
     */
    override val syncableFields: Map<String, Any?>
        get() = linkedMapOf(
            "name" to name,
            "is_income" to if (isIncome) 1 else 0,
            "hidden" to if (hidden) 1 else 0,
            "tombstone" to 0,
            "sort_order" to sortOrder,
        )

    companion object {
        const val DATASET_NAME = "category_groups"
    }
}

/**
 * Points a category id at the category its transactions should resolve to —
 * itself for a fresh category, the survivor once a category is merged away.
 * Every read path joins through this table, so upstream's `insertCategory`
 * writes the self-mapping row alongside the category itself, the same pair
 * `payee_mapping` gets.
 */
data class CategoryMapping(
    override val id: String,
    val targetId: String,
) : CRDTSyncable {
    override val datasetName: String get() = DATASET_NAME

    override val syncableFields: Map<String, Any?> get() = linkedMapOf("transferId" to targetId)

    companion object {
        const val DATASET_NAME = "category_mapping"
    }
}
