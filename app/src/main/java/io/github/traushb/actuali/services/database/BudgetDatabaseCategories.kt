package io.github.traushb.actuali.services.database

import io.github.traushb.actuali.models.Category
import io.github.traushb.actuali.models.CategoryGroup
import io.github.traushb.actuali.models.Payee
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.database.BudgetDatabase.Companion.duplicateName
import java.time.LocalDate

suspend fun BudgetDatabase.fetchCategoryGroups(): List<CategoryGroup> = dbQueue.readAsync { db ->
    val groups = db.fetchAll(
        """
        SELECT id, name, is_income, sort_order, hidden FROM category_groups
        WHERE (tombstone = 0 OR tombstone IS NULL)
        ORDER BY sort_order ASC
        """,
    )
    val categories = db.fetchAll(
        """
        SELECT id, name, is_income, cat_group, sort_order, hidden FROM categories
        WHERE (tombstone = 0 OR tombstone IS NULL)
        ORDER BY sort_order ASC
        """,
    )
    groups.map { group ->
        val groupId = group.string("id") ?: ""
        CategoryGroup(
            id = groupId,
            name = group.string("name") ?: "Unknown",
            isIncome = group.long("is_income") == 1L,
            hidden = group.long("hidden") == 1L,
            sortOrder = group.double("sort_order") ?: 0.0,
            categories = categories.filter { it.string("cat_group") == groupId }.map { cat ->
                Category(
                    id = cat.string("id") ?: "",
                    name = cat.string("name") ?: "Unknown",
                    groupId = cat.string("cat_group") ?: "",
                    isIncome = cat.long("is_income") == 1L,
                    hidden = cat.long("hidden") == 1L,
                    sortOrder = cat.double("sort_order") ?: 0.0,
                )
            },
        )
    }
}

/** Everything a category insert wrote: the new row, plus the siblings the shove moved to make room. */
data class CategoryInsertion(val category: Category, val movedSiblings: List<SortOrder.Position>)

/**
 * Refusals that come from the budget's own contents rather than SQLite,
 * worded for the person who typed the name. Upstream rejects the same two
 * cases in `insertCategoryGroup` / `insertCategory`.
 */
sealed class CategoryWriteError : Exception() {
    data class DuplicateGroupName(val name: String) : CategoryWriteError() {
        override val message: String get() = L("A category group named \"%@\" already exists", name)
    }

    data class DuplicateCategoryName(val name: String, val groupName: String) : CategoryWriteError() {
        override val message: String get() = L("%@ already has a category named \"%@\"", groupName, name)
    }

    object GroupNotFound : CategoryWriteError() {
        override val message: String get() = L("That category group no longer exists")
    }

    object CategoryNotFound : CategoryWriteError() {
        override val message: String get() = L("That category no longer exists")
    }
}

/**
 * Validate a group rename before emitting its CRDT message. Group names
 * remain unique across the budget, matching creation and upstream Actual.
 */
fun BudgetDatabase.validateCategoryGroupRename(id: String, name: String) = dbQueue.read { db ->
    db.fetchOne("SELECT 1 FROM category_groups WHERE id = ? AND tombstone IS NOT 1", id)
        ?: throw CategoryWriteError.GroupNotFound
    val names = db.strings("SELECT name FROM category_groups WHERE id != ? AND name IS NOT NULL AND tombstone IS NOT 1", id)
    duplicateName(name, names)?.let { throw CategoryWriteError.DuplicateGroupName(it) }
}

/**
 * Validate a category rename before the sync layer emits its name message.
 * Names remain unique within a group, matching category creation and the
 * web app.
 */
fun BudgetDatabase.validateCategoryRename(id: String, name: String) = dbQueue.read { db ->
    val row = db.fetchOne("SELECT cat_group FROM categories WHERE id = ? AND tombstone IS NOT 1", id)
        ?: throw CategoryWriteError.CategoryNotFound
    val groupId = row.string("cat_group") ?: ""
    val groupName = db.string("SELECT name FROM category_groups WHERE id = ? AND tombstone IS NOT 1", groupId) ?: "That group"
    val names = db.strings(
        "SELECT name FROM categories WHERE cat_group = ? AND id != ? AND name IS NOT NULL AND tombstone IS NOT 1",
        groupId, id,
    )
    if (duplicateName(name, names) != null) throw CategoryWriteError.DuplicateCategoryName(name, groupName)
}

/**
 * Create a category group after every existing one, mirroring upstream
 * `insertCategoryGroup`: names are unique across the whole budget
 * (case-insensitively), and the group sorts one increment past the last.
 * Returns the row as written, so the caller can turn it into CRDT messages.
 */
fun BudgetDatabase.insertCategoryGroup(id: String, name: String): CategoryGroup = dbQueue.write { db ->
    val names = db.strings("SELECT name FROM category_groups WHERE name IS NOT NULL AND tombstone IS NOT 1")
    duplicateName(name, names)?.let { throw CategoryWriteError.DuplicateGroupName(it) }

    val lastSortOrder = db.double(
        """
        SELECT sort_order FROM category_groups
        WHERE tombstone IS NOT 1
        ORDER BY sort_order DESC, id DESC
        LIMIT 1
        """,
    ) ?: 0.0

    val group = CategoryGroup(id, name, isIncome = false, hidden = false, sortOrder = lastSortOrder + SortOrder.INCREMENT, categories = emptyList())
    db.execute(
        "INSERT INTO category_groups (id, name, is_income, hidden, tombstone, sort_order) VALUES (?, ?, 0, 0, 0, ?)",
        group.id, group.name, group.sortOrder,
    )
    group
}

private fun Db.positions(sql: String, vararg args: Any?): List<SortOrder.Position> =
    fetchAll(sql, args.toList()).map { SortOrder.Position(it.string("id") ?: "", it.double("sort_order") ?: 0.0) }

/**
 * Create a category at the top of its group, mirroring upstream
 * `insertCategory`: names are unique within the group, the new row takes its
 * group's income and hidden flags, and it gets the self-referencing
 * `category_mapping` row every read path joins through. Siblings the shove
 * moved are written here too and returned for the caller's CRDT messages.
 */
fun BudgetDatabase.insertCategory(id: String, name: String, groupId: String): CategoryInsertion = dbQueue.write { db ->
    val group = db.fetchOne("SELECT name, is_income, hidden FROM category_groups WHERE id = ? AND tombstone IS NOT 1", groupId)
        ?: throw CategoryWriteError.GroupNotFound
    val groupName = group.string("name") ?: "That group"

    val names = db.strings("SELECT name FROM categories WHERE cat_group = ? AND name IS NOT NULL AND tombstone IS NOT 1", groupId)
    if (duplicateName(name, names) != null) throw CategoryWriteError.DuplicateCategoryName(name, groupName)

    val siblings = db.positions(
        "SELECT id, sort_order FROM categories WHERE cat_group = ? AND tombstone IS NOT 1 ORDER BY sort_order, id",
        groupId,
    )
    val placement = SortOrder.shove(siblings, siblings.firstOrNull()?.id)
    for (moved in placement.moved) {
        db.execute("UPDATE categories SET sort_order = ? WHERE id = ?", moved.sortOrder, moved.id)
    }

    val category = Category(
        id = id,
        name = name,
        groupId = groupId,
        isIncome = group.long("is_income") == 1L,
        hidden = group.long("hidden") == 1L,
        sortOrder = placement.sortOrder,
    )
    db.execute(
        "INSERT INTO categories (id, name, cat_group, is_income, hidden, tombstone, sort_order) VALUES (?, ?, ?, ?, ?, 0, ?)",
        category.id, category.name, category.groupId, if (category.isIncome) 1 else 0, if (category.hidden) 1 else 0, category.sortOrder,
    )
    db.execute("INSERT INTO category_mapping (id, transferId) VALUES (?, ?)", category.id, category.id)
    CategoryInsertion(category, placement.moved)
}

/** Where a category now sits after a move, plus the siblings the shove moved. */
data class CategoryMove(val id: String, val groupId: String, val sortOrder: Double, val movedSiblings: List<SortOrder.Position>)

/** Where a category group sits after a move, plus the siblings the shove moved. */
data class CategoryGroupMove(val id: String, val sortOrder: Double, val movedSiblings: List<SortOrder.Position>)

/**
 * Move a category group immediately before [targetId] (last when null or not
 * a group). Mirrors upstream `db.moveCategoryGroup`: every other live group,
 * income included, is ordered by sort_order and the group takes a midpoint
 * or a shove, leaving the moving group out of the list so its own old slot
 * isn't measured against.
 */
fun BudgetDatabase.moveCategoryGroup(id: String, targetId: String?): CategoryGroupMove = dbQueue.write { db ->
    db.fetchOne("SELECT 1 FROM category_groups WHERE id = ? AND tombstone IS NOT 1", id) ?: throw CategoryWriteError.GroupNotFound
    val siblings = db.positions(
        "SELECT id, sort_order FROM category_groups WHERE id != ? AND tombstone IS NOT 1 ORDER BY sort_order, id",
        id,
    )
    val placement = SortOrder.shove(siblings, targetId)
    for (moved in placement.moved) {
        db.execute("UPDATE category_groups SET sort_order = ? WHERE id = ?", moved.sortOrder, moved.id)
    }
    db.execute("UPDATE category_groups SET sort_order = ? WHERE id = ?", placement.sortOrder, id)
    CategoryGroupMove(id, placement.sortOrder, placement.moved)
}

/**
 * Move a category into [groupId], immediately before [targetId] (last when
 * null or not in that group). Mirrors upstream `db.moveCategory`: the target
 * group's other categories are ordered by sort_order, the new position is a
 * midpoint or a shove, and the category takes the group and position in one
 * write.
 */
fun BudgetDatabase.moveCategory(id: String, groupId: String, targetId: String?): CategoryMove = dbQueue.write { db ->
    db.fetchOne("SELECT 1 FROM category_groups WHERE id = ? AND tombstone IS NOT 1", groupId) ?: throw CategoryWriteError.GroupNotFound
    db.fetchOne("SELECT 1 FROM categories WHERE id = ? AND tombstone IS NOT 1", id) ?: throw CategoryWriteError.CategoryNotFound
    val siblings = db.positions(
        "SELECT id, sort_order FROM categories WHERE cat_group = ? AND id != ? AND tombstone IS NOT 1 ORDER BY sort_order, id",
        groupId, id,
    )
    val placement = SortOrder.shove(siblings, targetId)
    for (moved in placement.moved) {
        db.execute("UPDATE categories SET sort_order = ? WHERE id = ?", moved.sortOrder, moved.id)
    }
    db.execute("UPDATE categories SET cat_group = ?, sort_order = ? WHERE id = ?", groupId, placement.sortOrder, id)
    CategoryMove(id, groupId, placement.sortOrder, placement.moved)
}

// Payees

suspend fun BudgetDatabase.fetchPayees(): List<Payee> = dbQueue.readAsync { db ->
    db.fetchAll(
        """
        SELECT id, name, transfer_acct FROM payees
        WHERE (tombstone = 0 OR tombstone IS NULL)
        ORDER BY name ASC
        """,
    ).map { Payee(it.string("id") ?: "", it.string("name") ?: "Unknown", it.string("transfer_acct")) }
}

/**
 * Payees used most often in the last 12 weeks, matching Actual's
 * `getCommonPayees()` behavior. Ordered by usage count, then by payee name.
 */
suspend fun BudgetDatabase.fetchCommonPayees(): List<Payee> = dbQueue.readAsync { db ->
    val cutoffDate = Transaction.yyyymmdd(LocalDate.now().minusWeeks(12))
    db.fetchAll(
        """
        SELECT
            p.id,
            p.name,
            p.transfer_acct,
            COUNT(t.id) AS usage_count
        FROM payees p
        JOIN payee_mapping pm ON pm.targetId = p.id
        JOIN transactions t ON t.description = pm.id
        WHERE LENGTH(p.name) > 0
          AND p.transfer_acct IS NULL
          AND (p.tombstone = 0 OR p.tombstone IS NULL)
          AND t.date > ?
          AND (t.tombstone = 0 OR t.tombstone IS NULL)
          AND (t.isChild = 0 OR t.isChild IS NULL)
        GROUP BY p.id
        ORDER BY usage_count DESC, p.name COLLATE NOCASE ASC
        LIMIT 10
        """,
        cutoffDate,
    ).map { Payee(it.string("id") ?: "", it.string("name") ?: "Unknown", it.string("transfer_acct")) }
}

/**
 * The category id of the most recent non-tombstoned transaction for
 * [payeeId] where category is non-null, or null if none exists. (In this
 * schema `description` stores the payee id.)
 */
suspend fun BudgetDatabase.mostRecentCategoryId(payeeId: String): String? = dbQueue.readAsync { db ->
    db.string(
        """
        SELECT category FROM transactions
        WHERE description = ?
          AND (tombstone = 0 OR tombstone IS NULL)
          AND category IS NOT NULL
        ORDER BY date DESC, sort_order DESC
        LIMIT 1
        """,
        payeeId,
    )
}
