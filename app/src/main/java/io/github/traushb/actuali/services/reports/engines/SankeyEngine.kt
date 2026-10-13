package io.github.traushb.actuali.services.reports.engines

import io.github.traushb.actuali.models.CategoryGroup
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.services.ReportStrings
import io.github.traushb.actuali.services.reports.ConditionsFilter
import io.github.traushb.actuali.services.reports.TimeFrame
import io.github.traushb.actuali.services.reports.WidgetRuleCondition
import io.github.traushb.actuali.services.reports.WidgetTimeFrame
import io.github.traushb.actuali.services.reports.conditionStrings
import io.github.traushb.actuali.services.reports.monthEnd
import io.github.traushb.actuali.services.reports.monthStart
import io.github.traushb.actuali.services.reports.ymd
import io.github.traushb.actuali.services.stringOrNull
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Mirrors upstream `SankeyWidget['meta']` (sankey-card). */
@Serializable
data class SankeyMeta(
    val name: String? = null,
    val conditions: List<WidgetRuleCondition>? = null,
    val conditionsOp: String? = null,
    val timeFrame: WidgetTimeFrame? = null,
    /** "budgeted" | "spent"; null = "spent" */
    val mode: String? = null,
    val topNcategories: Int? = null,
    /** "per-group" | "global" | "budget-order" */
    val categorySort: String? = null,
    val showPercentages: Boolean? = null,
    val groupAccounts: Boolean? = null,
    /** SankeyLayer raw value */
    val layerFrom: String? = null,
    val layerTo: String? = null,
)

/** Upstream `GraphLayers`; entry order is GRAPH_LAYER_ORDER. */
enum class SankeyLayer(val rawValue: String) {
    IncomePayee("payee"),
    IncomeCategory("income_category"),
    Account("account"),
    Budget("budget"),
    CategoryGroup("category_group"),
    Category("category"),
    ;

    companion object {
        fun fromRaw(raw: String?): SankeyLayer? = entries.firstOrNull { it.rawValue == raw }
    }
}

enum class SankeySortMode(val rawValue: String) {
    PerGroup("per-group"),
    Global("global"),
    BudgetOrder("budget-order"),
    ;

    companion object {
        fun fromRaw(raw: String?): SankeySortMode? = entries.firstOrNull { it.rawValue == raw }
    }
}

data class SankeyData(val nodes: List<Node>, val links: List<Link>, val showPercentages: Boolean) {
    data class Node(val key: String, val name: String, val percentageLabel: String, val layer: SankeyLayer, val isNegative: Boolean) {
        val isHidden: Boolean get() = key.endsWith("__HIDDEN")
    }

    data class Link(
        val source: Int,
        val target: Int,
        /** cents; hidden layout-scaffolding links carry -1 */
        val value: Long,
    )

    companion object {
        val empty = SankeyData(emptyList(), emptyList(), false)
    }
}

/**
 * Insertion-ordered directed graph mirroring upstream's `Graph` (a JS Map;
 * Map iteration order is insertion order, which the sort/layout relies on).
 * [copy] is the deep clone upstream takes before mutating.
 */
class SankeyGraph private constructor(private var order: MutableList<String>, private val nodes: LinkedHashMap<String, Node>) {
    constructor() : this(mutableListOf(), LinkedHashMap())

    class Node(var type: SankeyLayer, var name: String?, var isNegative: Boolean = false) {
        var percentageLabel: String? = null
        val toOrder = mutableListOf<String>()
        val toValues = HashMap<String, Long>()

        val hasChild: Boolean get() = toOrder.isNotEmpty()

        fun copy() = Node(type, name, isNegative).also {
            it.percentageLabel = percentageLabel
            it.toOrder += toOrder
            it.toValues += toValues
        }
    }

    val keys: List<String> get() = order.toList()

    operator fun get(key: String): Node? = nodes[key]

    fun copy() = SankeyGraph(order.toMutableList(), LinkedHashMap(nodes.mapValues { it.value.copy() }))

    fun addNode(key: String, type: SankeyLayer, name: String?, isNegative: Boolean = false) {
        if (key in nodes) return
        nodes[key] = Node(type, name, isNegative)
        order += key
    }

    fun addValueToLink(from: String, to: String, value: Long) {
        val node = nodes[from] ?: return
        if (to !in node.toValues) node.toOrder += to
        node.toValues[to] = (node.toValues[to] ?: 0) + value
    }

    fun deleteLink(from: String, to: String) {
        val node = nodes[from] ?: return
        node.toValues.remove(to)
        node.toOrder.removeAll { it == to }
    }

    fun delete(key: String) {
        nodes.remove(key)
        order.removeAll { it == key }
    }

    fun reorder(newOrder: List<String>) {
        order = newOrder.toMutableList()
    }

    fun links(from: String): List<Pair<String, Long>> {
        val node = nodes[from] ?: return emptyList()
        return node.toOrder.map { it to (node.toValues[it] ?: 0) }
    }
}

/**
 * Upstream `CategoryEntry`. Spent mode stores `value` as abs(cents); budgeted
 * mode keeps the sign (matching upstream, whose createBudgetGraph branches on
 * the raw signed value).
 */
data class SankeyCategoryEntry(
    val categoryGroup: String,
    val categoryGroupId: String,
    val category: String,
    val categoryId: String,
    val value: Long,
    val isIncome: Boolean,
    val isNegative: Boolean,
    val accountName: String? = null,
    val accountId: String? = null,
    val payeeName: String? = null,
    val payeeId: String? = null,
)

/**
 * Budget-mode input. `entries` come from zero_budgets/reflect_budgets rows
 * (month YYYYMM, category, amount cents). The envelope aggregates mirror
 * upstream `api/budget-month`: [toBudgetCents] is "to budget" of the range's
 * LAST month, [fromPreviousMonthCents] fromLastMonth of its FIRST month,
 * [lastMonthOverspentCents] the sum of lastMonthOverspent across the range,
 * [forNextMonthCents] nextMonth(end).fromLastMonth - toBudget. Leaving them 0
 * simply omits those flows (zero links are cleaned up).
 */
data class SankeyBudgetInput(
    val entries: List<Entry> = emptyList(),
    val toBudgetCents: Long = 0,
    val fromPreviousMonthCents: Long = 0,
    val lastMonthOverspentCents: Long = 0,
    val forNextMonthCents: Long = 0,
) {
    data class Entry(val month: Int, val categoryId: String, val amountCents: Long)
}

/**
 * Port of the webapp's sankey-spreadsheet.ts at dashboard-card fidelity.
 * Deliberate cuts vs upstream: no tooltip info, no colors (the view derives
 * them), labels localized inline at node creation.
 */
object SankeyEngine {
    object SpecialKey {
        const val TO_BUDGET = "to_budget"
        const val BUDGETED = "budgeted"
        const val LAST_MONTH_OVERSPENT = "last_month_overspent"
        const val FOR_NEXT_MONTH = "for_next_month"
        const val FROM_PREV_MONTH = "from_previous_month"
        const val AVAILABLE_INCOME = "available_income"
        const val ALL_ACCOUNTS = "all_income"
        const val OTHER_SUFFIX = "__OTHER_BUCKET"
        const val HIDDEN_SUFFIX = "__HIDDEN"
        const val NEGATIVE_SUFFIX = "__NEGATIVE"

        /** Upstream builds this from Object.values(SpecialNodeKeys), which includes the literal suffix strings. */
        val structural = setOf(
            TO_BUDGET, BUDGETED, LAST_MONTH_OVERSPENT, FOR_NEXT_MONTH, FROM_PREV_MONTH, AVAILABLE_INCOME, ALL_ACCOUNTS,
            OTHER_SUFFIX, HIDDEN_SUFFIX, NEGATIVE_SUFFIX,
        )
    }

    /**
     * Account display names come from `context.accountNames` (the same map
     * the conditions filter uses); missing entries fall back to the id so the
     * card still renders. [maxNodesPerLayer] mirrors the card's height-based
     * cap (upstream topNNodes: 35px per node, ~6 at card height) and is min'd
     * with `meta.topNcategories ?: 15` like SankeyCard.tsx.
     */
    fun compute(
        meta: SankeyMeta?,
        transactions: List<Transaction>,
        categoryGroups: List<CategoryGroup>,
        budget: SankeyBudgetInput? = null,
        today: LocalDate,
        context: ConditionsFilter.Context = ConditionsFilter.Context.empty,
        maxNodesPerLayer: Int = 6,
        locale: Locale = Locale.getDefault(),
    ): SankeyData {
        val (resolvedStart, resolvedEnd) = TimeFrame.resolve(meta?.timeFrame, today)
        // Upstream queries firstDayOfMonth(start)..lastDayOfMonth(end).
        val start = resolvedStart.monthStart()
        val end = resolvedEnd.monthEnd()
        val startYMD = start.ymd
        val endYMD = end.ymd

        val isBudgeted = meta?.mode == "budgeted"
        // AQL `transactions` defaults to inline splits: children shown, parents excluded.
        val live = transactions.filter { !it.tombstone && !it.isParent && it.date in startYMD..endYMD }

        val graph = if (isBudgeted) {
            val input = budget ?: SankeyBudgetInput()
            createBudgetGraph(
                budgetEntries(categoryGroups, meta?.conditions, meta?.conditionsOp, input, live, monthsInRange(start, end)),
                input, monthLabel(start), monthLabel(end), locale,
            )
        } else {
            createTransactionsGraph(
                transactionEntries(categoryGroups, live, meta?.conditions, meta?.conditionsOp, meta?.groupAccounts ?: false, context),
                locale,
            )
        }

        val topN = minOf(meta?.topNcategories ?: 15, maxNodesPerLayer)
        val sort = SankeySortMode.fromRaw(meta?.categorySort) ?: SankeySortMode.PerGroup

        val from = SankeyLayer.fromRaw(meta?.layerFrom)
        val to = SankeyLayer.fromRaw(meta?.layerTo)
        // Upstream getDefaultLayerRange(mode).
        val (layerFrom, layerTo) = if (from != null && to != null) {
            from to to
        } else {
            (if (isBudgeted) SankeyLayer.IncomeCategory else SankeyLayer.IncomePayee) to SankeyLayer.Category
        }

        return buildSankeyData(graph, topN, categoryGroups, sort, layerFrom, layerTo, meta?.showPercentages ?: false, locale)
    }

    // Entry building (spent)

    /**
     * Upstream fetchCategoryData: per category, transactions grouped by
     * account (expense) or account+payee (income), summed.
     */
    fun transactionEntries(
        categoryGroups: List<CategoryGroup>,
        transactions: List<Transaction>,
        conditions: List<WidgetRuleCondition>?,
        conditionsOp: String?,
        groupAccounts: Boolean,
        context: ConditionsFilter.Context,
    ): List<SankeyCategoryEntry> {
        class Bucket(val accountId: String, val payeeId: String?, val payeeName: String?, var sum: Long = 0)

        val filtered = transactions.filter { ConditionsFilter.matches(it, conditions, conditionsOp, context) }
        val entries = mutableListOf<SankeyCategoryEntry>()
        for (group in categoryGroups) {
            for (cat in group.categories) {
                val buckets = LinkedHashMap<String, Bucket>()
                for (tx in filtered) {
                    if (tx.categoryId != cat.id) continue
                    val key = if (group.isIncome) tx.accountId + "|" + (tx.payeeId ?: "") else tx.accountId
                    buckets.getOrPut(key) {
                        Bucket(tx.accountId, if (group.isIncome) tx.payeeId else null, if (group.isIncome) tx.payeeName else null)
                    }.sum += tx.amount
                }
                for (b in buckets.values) {
                    entries += SankeyCategoryEntry(
                        categoryGroup = group.name,
                        categoryGroupId = group.id,
                        category = cat.name,
                        categoryId = cat.id,
                        value = abs(b.sum),
                        isIncome = group.isIncome,
                        isNegative = b.sum < 0,
                        accountName = context.accountNames[b.accountId] ?: b.accountId,
                        accountId = b.accountId,
                        payeeName = b.payeeName,
                        payeeId = b.payeeId,
                    )
                }
            }
        }
        if (!groupAccounts) return entries
        return entries.map { e ->
            if (!e.accountId.isNullOrEmpty() && !e.accountName.isNullOrEmpty()) e.copy(accountId = SpecialKey.ALL_ACCOUNTS, accountName = SpecialKey.ALL_ACCOUNTS) else e
        }
    }

    fun createTransactionsGraph(categoryData: List<SankeyCategoryEntry>, locale: Locale = Locale.getDefault()): SankeyGraph {
        val graph = SankeyGraph()

        fun addAccountNode(accountId: String, accountName: String) {
            graph.addNode(accountId, SankeyLayer.Account, if (accountId == SpecialKey.ALL_ACCOUNTS) ReportStrings.text("Income", locale) else accountName)
        }

        for (entry in categoryData) {
            val accountId = entry.accountId
            val accountName = entry.accountName
            if (accountId.isNullOrEmpty() || accountName.isNullOrEmpty() || entry.categoryId.isEmpty()) continue
            // Upstream falls back `payeeName ?? category`; we also treat the
            // empty string as missing (upstream's AQL always yields '').
            val payeeOrCategory = entry.payeeName?.takeIf { it.isNotEmpty() } ?: entry.category

            if (entry.isIncome) {
                if (entry.isNegative) {
                    // Account > (negative income shown as an outflow bucket)
                    addAccountNode(accountId, accountName)
                    val negKey = entry.categoryId + SpecialKey.NEGATIVE_SUFFIX
                    graph.addNode(negKey, SankeyLayer.CategoryGroup, payeeOrCategory, isNegative = true)
                    graph.addValueToLink(accountId, negKey, entry.value)
                } else {
                    // Payee > Income category > Account
                    graph.addNode(entry.categoryId, SankeyLayer.IncomeCategory, entry.category)
                    addAccountNode(accountId, accountName)
                    graph.addValueToLink(entry.categoryId, accountId, entry.value)
                    val payeeId = entry.payeeId
                    if (!payeeId.isNullOrEmpty()) {
                        graph.addNode(payeeId, SankeyLayer.IncomePayee, entry.payeeName)
                        graph.addValueToLink(payeeId, entry.categoryId, entry.value)
                    }
                }
            } else if (entry.isNegative) {
                // Account > Category group > Category
                addAccountNode(accountId, accountName)
                graph.addNode(entry.categoryGroupId, SankeyLayer.CategoryGroup, entry.categoryGroup)
                graph.addNode(entry.categoryId, SankeyLayer.Category, entry.category)
                graph.addValueToLink(accountId, entry.categoryGroupId, entry.value)
                graph.addValueToLink(entry.categoryGroupId, entry.categoryId, entry.value)
            } else {
                // Refund: Category (as income-side node) > Account
                val negKey = entry.categoryId + SpecialKey.NEGATIVE_SUFFIX
                graph.addNode(negKey, SankeyLayer.IncomeCategory, payeeOrCategory)
                addAccountNode(accountId, accountName)
                graph.addValueToLink(negKey, accountId, entry.value)
            }
        }
        return graph
    }

    // Entry building (budgeted)

    /**
     * Income categories use "received" (sum of transactions in range, NOT
     * filtered by conditions — upstream budget-month data is unconditioned);
     * expense categories use budgeted amounts. Zero entries are dropped.
     */
    fun budgetEntries(
        categoryGroups: List<CategoryGroup>,
        conditions: List<WidgetRuleCondition>?,
        conditionsOp: String?,
        budget: SankeyBudgetInput,
        transactions: List<Transaction>,
        months: Set<Int>,
    ): List<SankeyCategoryEntry> {
        val budgetedByCat = HashMap<String, Long>()
        for (entry in budget.entries) if (entry.month in months) budgetedByCat.merge(entry.categoryId, entry.amountCents, Long::plus)

        val entries = mutableListOf<SankeyCategoryEntry>()
        for (group in filterCategoryGroups(categoryGroups, conditions, conditionsOp)) {
            for (cat in group.categories) {
                val raw = if (group.isIncome) transactions.filter { it.categoryId == cat.id }.sumOf { it.amount } else budgetedByCat[cat.id] ?: 0
                if (raw == 0L) continue
                entries += SankeyCategoryEntry(group.name, group.id, cat.name, cat.id, raw, group.isIncome, raw < 0)
            }
        }
        return entries
    }

    /**
     * Upstream filterCategoryGroups: budget data is fetched unconditionally,
     * so category/category_group conditions are applied manually.
     */
    fun filterCategoryGroups(groups: List<CategoryGroup>, conditions: List<WidgetRuleCondition>?, conditionsOp: String?): List<CategoryGroup> {
        val all = conditions.orEmpty()
        val catConds = all.filter { it.field == "category" }
        val groupConds = all.filter { it.field == "category_group" }
        if (catConds.isEmpty() && groupConds.isEmpty()) return groups

        val isOr = (conditionsOp ?: "and").lowercase() == "or"
        fun matches(catId: String, catName: String, groupId: String, groupName: String): Boolean {
            val matchesCat = { c: WidgetRuleCondition -> matchesStringCondition(catId, catName, c) }
            val matchesGroup = { c: WidgetRuleCondition -> matchesStringCondition(groupId, groupName, c) }
            if (isOr) return catConds.any(matchesCat) || groupConds.any(matchesGroup)
            return (catConds.isEmpty() || catConds.all(matchesCat)) && (groupConds.isEmpty() || groupConds.all(matchesGroup))
        }

        return groups
            .map { group -> group.copy(categories = group.categories.filter { matches(it.id, it.name, group.id, group.name) }) }
            .filter { it.categories.isNotEmpty() }
    }

    private fun matchesStringCondition(id: String, name: String, condition: WidgetRuleCondition): Boolean {
        val value = condition.value.stringOrNull
        return when (condition.op) {
            "is" -> id == value
            "isNot" -> id != value
            "oneOf" -> condition.value.conditionStrings()?.contains(id) ?: false
            "notOneOf" -> !(condition.value.conditionStrings()?.contains(id) ?: false)
            "contains" -> value != null && name.lowercase().contains(value.lowercase())
            "doesNotContain" -> value != null && !name.lowercase().contains(value.lowercase())
            "matches" -> {
                if (value == null || value.length > 256) return false
                // Upstream accepts "/pattern/" or a bare pattern, case-insensitive.
                val lastSlash = value.lastIndexOf('/')
                val pattern = if (value.startsWith("/") && lastSlash > 0) value.substring(1, lastSlash) else value
                val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: return false
                regex.containsMatchIn(name)
            }
            else -> false
        }
    }

    fun createBudgetGraph(
        categoryData: List<SankeyCategoryEntry>,
        budget: SankeyBudgetInput,
        startMonth: String,
        endMonth: String,
        locale: Locale = Locale.getDefault(),
    ): SankeyGraph {
        val graph = SankeyGraph()
        graph.addNode(SpecialKey.BUDGETED, SankeyLayer.Budget, ReportStrings.text("Budgeted", locale))
        graph.addNode(SpecialKey.AVAILABLE_INCOME, SankeyLayer.Account, ReportStrings.text("Available funds", locale))

        for (entry in categoryData) {
            if (entry.isIncome) {
                // Income category > Available income
                graph.addNode(entry.categoryId, SankeyLayer.IncomeCategory, entry.category)
                graph.addValueToLink(entry.categoryId, SpecialKey.AVAILABLE_INCOME, entry.value)
            } else if (entry.value >= 0) {
                // Budgeted > Category group > Category
                graph.addNode(entry.categoryGroupId, SankeyLayer.CategoryGroup, entry.categoryGroup)
                graph.addNode(entry.categoryId, SankeyLayer.Category, entry.category)
                graph.addValueToLink(entry.categoryGroupId, entry.categoryId, entry.value)
                graph.addValueToLink(SpecialKey.BUDGETED, entry.categoryGroupId, entry.value)
                graph.addValueToLink(SpecialKey.AVAILABLE_INCOME, SpecialKey.BUDGETED, entry.value)
            } else {
                // Negative budget: category feeds the budget pool
                graph.addNode(entry.categoryId, SankeyLayer.Account, ReportStrings.format("From %@", entry.category, locale = locale), isNegative = true)
                graph.addValueToLink(entry.categoryId, SpecialKey.BUDGETED, abs(entry.value))
                graph.addValueToLink(SpecialKey.AVAILABLE_INCOME, SpecialKey.BUDGETED, entry.value)
            }
        }

        if (budget.toBudgetCents > 0) {
            graph.addNode(SpecialKey.TO_BUDGET, SankeyLayer.CategoryGroup, ReportStrings.text("To budget", locale))
            graph.addValueToLink(SpecialKey.AVAILABLE_INCOME, SpecialKey.TO_BUDGET, budget.toBudgetCents)
        } else {
            graph.addNode(SpecialKey.TO_BUDGET, SankeyLayer.Account, ReportStrings.text("Overbudgeted", locale), isNegative = true)
            graph.addValueToLink(SpecialKey.TO_BUDGET, SpecialKey.BUDGETED, abs(budget.toBudgetCents))
            graph.addValueToLink(SpecialKey.AVAILABLE_INCOME, SpecialKey.BUDGETED, -abs(budget.toBudgetCents))
        }

        graph.addNode(
            SpecialKey.FROM_PREV_MONTH, SankeyLayer.IncomeCategory,
            ReportStrings.format("From %@", localizedMonthLabel(shiftMonth(startMonth, -1), locale), locale = locale),
        )
        graph.addValueToLink(SpecialKey.FROM_PREV_MONTH, SpecialKey.AVAILABLE_INCOME, budget.fromPreviousMonthCents)
        graph.addNode(
            SpecialKey.FOR_NEXT_MONTH, SankeyLayer.Budget,
            ReportStrings.format("For %@", localizedMonthLabel(shiftMonth(endMonth, 1), locale), locale = locale),
        )
        graph.addValueToLink(SpecialKey.AVAILABLE_INCOME, SpecialKey.FOR_NEXT_MONTH, budget.forNextMonthCents)
        graph.addNode(SpecialKey.LAST_MONTH_OVERSPENT, SankeyLayer.Budget, ReportStrings.text("Overspent", locale))
        graph.addValueToLink(SpecialKey.AVAILABLE_INCOME, SpecialKey.LAST_MONTH_OVERSPENT, abs(budget.lastMonthOverspentCents))
        return graph
    }

    // Pipeline (upstream buildSankeyData)

    fun buildSankeyData(
        baseGraph: SankeyGraph,
        topN: Int,
        categoryGroups: List<CategoryGroup>,
        categorySort: SankeySortMode,
        layerFrom: SankeyLayer,
        layerTo: SankeyLayer,
        showPercentages: Boolean = false,
        locale: Locale = Locale.getDefault(),
    ): SankeyData {
        val graph = baseGraph.copy() // upstream clones
        groupOtherCategories(graph, topN, categorySort, locale)
        sortGraph(graph, categorySort, categoryGroups)
        addPercentageLabels(graph, locale)
        cleanUpNodes(graph)
        addHiddenNodes(graph)
        filterGraphByLayers(graph, layerFrom, layerTo)
        return convertToSankeyData(graph, showPercentages)
    }

    // Graph queries

    fun getLayer(graph: SankeyGraph, key: String): Int {
        val parents = graph.keys.filter { graph[it]?.toValues?.containsKey(key) == true }
        if (parents.isEmpty()) return 0
        return 1 + parents.maxOf { getLayer(graph, it) }
    }

    fun getNodeValue(graph: SankeyGraph, key: String): Long {
        if (getLayer(graph, key) == 0) return graph[key]?.toValues?.values?.sum() ?: 0
        var value = graph.keys.sumOf { graph[it]?.toValues?.get(key) ?: 0L }
        // Root node masked behind hidden (-1) links
        if (value < 0) value = graph[key]?.toValues?.values?.sum() ?: 0
        return value
    }

    fun nodesInLayer(graph: SankeyGraph, layer: SankeyLayer): List<String> = graph.keys.filter { graph[it]?.type == layer }

    fun hasParent(graph: SankeyGraph, key: String): Boolean = graph.keys.any { graph[it]?.toValues?.containsKey(key) == true }

    fun getCategoryGroup(graph: SankeyGraph, key: String): String? =
        graph.keys.firstOrNull { graph[it]?.toValues?.containsKey(key) == true && graph[it]?.type == SankeyLayer.CategoryGroup }

    // Other-bucket grouping

    fun groupOtherCategories(graph: SankeyGraph, topN: Int, categorySort: SankeySortMode, locale: Locale = Locale.getDefault()) {
        fun isGroupable(key: String): Boolean {
            val node = graph[key] ?: return false
            return !key.endsWith(SpecialKey.OTHER_SUFFIX) && key !in SpecialKey.structural && !node.isNegative
        }

        for (layer in SankeyLayer.entries) {
            var ordinary = nodesInLayer(graph, layer).filter(::isGroupable)
            var others = nodesInLayer(graph, layer).filter { it.endsWith(SpecialKey.OTHER_SUFFIX) }
            while (ordinary.size + others.size > topN) {
                // minByOrNull keeps the first of equal elements, matching the
                // upstream strict `<` scan.
                val nodeToDelete = ordinary.minByOrNull { getNodeValue(graph, it) } ?: break
                moveToOther(graph, nodeToDelete, categorySort == SankeySortMode.Global, locale)
                ordinary = nodesInLayer(graph, layer).filter(::isGroupable)
                others = nodesInLayer(graph, layer).filter { it.endsWith(SpecialKey.OTHER_SUFFIX) }
            }
        }
    }

    fun moveToOther(graph: SankeyGraph, key: String, globalOther: Boolean, locale: Locale = Locale.getDefault()) {
        val nodeData = graph[key] ?: return
        val toNodes = nodeData.toOrder.toList()
        val fromNodes = graph.keys.filter { graph[it]?.toValues?.containsKey(key) == true }

        var otherKey = nodeData.type.rawValue + SpecialKey.OTHER_SUFFIX
        if (!globalOther) {
            val groupKey = if (nodeData.type == SankeyLayer.Category) getCategoryGroup(graph, key) else null
            val incomeCatKey = if (nodeData.type == SankeyLayer.IncomePayee) nodeData.toOrder.firstOrNull { graph[it]?.type == SankeyLayer.IncomeCategory } else null
            if (groupKey != null) otherKey = groupKey + SpecialKey.OTHER_SUFFIX
            else if (incomeCatKey != null) otherKey = incomeCatKey + SpecialKey.OTHER_SUFFIX
        }

        graph.addNode(otherKey, nodeData.type, ReportStrings.text("Other", locale))
        for (fromKey in fromNodes) graph[fromKey]?.toValues?.get(key)?.let { graph.addValueToLink(fromKey, otherKey, it) }
        for (toKey in toNodes) graph[key]?.toValues?.get(toKey)?.let { graph.addValueToLink(otherKey, toKey, it) }
        for (toKey in toNodes) graph.deleteLink(key, toKey)
        for (fromKey in fromNodes) graph.deleteLink(fromKey, key)
        graph.delete(key)
    }

    // Sorting

    fun sortGraph(graph: SankeyGraph, categorySort: SankeySortMode, categoryGroups: List<CategoryGroup>) {
        val snapshot = graph.copy()

        /** Stable value-descending sort (JS Array.sort is stable). */
        fun valueSorted(keys: List<String>): List<String> = keys.sortedByDescending { getNodeValue(snapshot, it) }

        fun sortRelatedNodesAroundAnchors(entries: MutableList<String>, anchorLayer: SankeyLayer, relatedLayer: SankeyLayer, placeAfter: Boolean) {
            for (anchorKey in nodesInLayer(snapshot, anchorLayer)) {
                val anchor = snapshot[anchorKey] ?: continue
                val relatedKeys = if (placeAfter) {
                    anchor.toOrder.filter { snapshot[it]?.type == relatedLayer }
                } else {
                    snapshot.keys.filter { snapshot[it]?.type == relatedLayer && snapshot[it]?.toValues?.containsKey(anchorKey) == true }
                }
                val otherKey = anchorKey + SpecialKey.OTHER_SUFFIX
                val relatedOtherKey = otherKey.takeIf { snapshot[it]?.type == relatedLayer }
                val orderedRelatedKeys = relatedKeys.filter { it != otherKey }
                val relatedEntries = valueSorted(orderedRelatedKeys.filter { it in entries })
                val otherEntry = relatedOtherKey?.takeIf { it in entries }

                val keysToMove = orderedRelatedKeys.toMutableSet()
                relatedOtherKey?.let { keysToMove += it }
                val without = entries.filter { it !in keysToMove }.toMutableList()
                val anchorIndex = without.indexOf(anchorKey)
                if (anchorIndex < 0) continue
                val nodesToInsert = relatedEntries + listOfNotNull(otherEntry)
                without.addAll(if (placeAfter) anchorIndex + 1 else anchorIndex, nodesToInsert)
                entries.clear()
                entries += without
            }
        }

        val entries: MutableList<String> = when (categorySort) {
            SankeySortMode.Global -> valueSorted(snapshot.keys).toMutableList().also { list ->
                for (key in list.filter { it.endsWith(SpecialKey.OTHER_SUFFIX) }) moveNodeToEnd(list, key)
            }
            SankeySortMode.PerGroup -> valueSorted(snapshot.keys).toMutableList().also { list ->
                sortRelatedNodesAroundAnchors(list, SankeyLayer.CategoryGroup, SankeyLayer.Category, placeAfter = true)
                sortRelatedNodesAroundAnchors(list, SankeyLayer.IncomeCategory, SankeyLayer.IncomePayee, placeAfter = false)
            }
            SankeySortMode.BudgetOrder -> {
                val used = HashSet<String>()
                val list = mutableListOf<String>()
                for (group in categoryGroups) {
                    if (snapshot[group.id] != null) {
                        list += group.id
                        used += group.id
                    }
                    for (cat in group.categories) {
                        if (snapshot[cat.id] != null) {
                            list += cat.id
                            used += cat.id
                        }
                    }
                    val otherKey = group.id + SpecialKey.OTHER_SUFFIX
                    if (snapshot[otherKey] != null) {
                        list += otherKey
                        used += otherKey
                    }
                }
                snapshot.keys.filterTo(list) { it !in used }
            }
        }

        // Pin certain nodes to the start/end of the graph.
        for (key in entries.filter { snapshot[it]?.isNegative == true }) moveNodeToStart(entries, key)
        snapshot[SpecialKey.TO_BUDGET]?.let { toBudgetNode ->
            if (toBudgetNode.isNegative) moveNodeToStart(entries, SpecialKey.TO_BUDGET) else moveNodeToEnd(entries, SpecialKey.TO_BUDGET)
        }
        moveNodeToEnd(entries, SpecialKey.LAST_MONTH_OVERSPENT)
        moveNodeToEnd(entries, SpecialKey.FOR_NEXT_MONTH)
        moveNodeToEnd(entries, SpecialKey.FROM_PREV_MONTH)
        graph.reorder(entries)
    }

    fun moveNodeToEnd(entries: MutableList<String>, key: String) {
        val index = entries.indexOf(key)
        if (index >= 0) entries += entries.removeAt(index)
    }

    fun moveNodeToStart(entries: MutableList<String>, key: String) {
        val index = entries.indexOf(key)
        if (index >= 0) entries.add(0, entries.removeAt(index))
    }

    // Labels / cleanup

    fun addPercentageLabels(graph: SankeyGraph, locale: Locale = Locale.getDefault()) {
        val layerSums = HashMap<SankeyLayer, Long>()
        for (key in graph.keys) {
            val layer = graph[key]?.type ?: continue
            layerSums.merge(layer, getNodeValue(graph, key), Long::plus)
        }
        val formatter = NumberFormat.getPercentInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        for (key in graph.keys) {
            val node = graph[key] ?: continue
            val total = layerSums[node.type] ?: 1
            val percentage = if (total != 0L) getNodeValue(graph, key).toDouble() / total else 0.0
            node.percentageLabel = formatter.format(percentage)
        }
    }

    fun cleanUpNodes(graph: SankeyGraph) {
        for (key in graph.keys) for ((to, value) in graph.links(key)) if (value == 0L) graph.deleteLink(key, to)
        val hasIncoming = HashSet<String>()
        for (key in graph.keys) graph[key]?.toOrder?.let(hasIncoming::addAll)
        for (key in graph.keys) if (graph[key]?.hasChild == false && key !in hasIncoming) graph.delete(key)
    }

    // Hidden layout nodes

    /**
     * Nodes whose parents/children sit in different layers than their peers
     * would land in the wrong column; invisible -1 chains pin them in place.
     */
    fun addHiddenNodes(graph: SankeyGraph) {
        val nodesByType = LinkedHashMap<SankeyLayer, MutableList<String>>()
        for (key in graph.keys) {
            val type = graph[key]?.type ?: continue
            nodesByType.getOrPut(type) { mutableListOf() } += key
        }
        val typeHasParent = nodesByType.mapValues { (_, keys) -> keys.any { hasParent(graph, it) } }
        val typeHasChild = nodesByType.mapValues { (_, keys) -> keys.any { graph[it]?.hasChild == true } }

        val activeLayerOrder = SankeyLayer.entries.filter { !nodesByType[it].isNullOrEmpty() }
        val layerIndex = activeLayerOrder.withIndex().associate { it.value to it.index }

        fun addHiddenParentChain(key: String, type: SankeyLayer) {
            val typeIndex = layerIndex[type] ?: return
            if (typeIndex <= 0) return
            var childKey = key
            for (i in typeIndex - 1 downTo 0) {
                val parentType = activeLayerOrder[i]
                val parentKey = "${key}_${parentType.rawValue}${SpecialKey.HIDDEN_SUFFIX}"
                graph.addNode(parentKey, parentType, "")
                graph.addValueToLink(parentKey, childKey, -1)
                childKey = parentKey
            }
        }

        fun addHiddenChildChain(key: String, type: SankeyLayer) {
            val typeIndex = layerIndex[type] ?: return
            if (typeIndex >= activeLayerOrder.size - 1) return
            var parentKey = key
            for (i in typeIndex + 1 until activeLayerOrder.size) {
                val childType = activeLayerOrder[i]
                val childKey = "${key}_${childType.rawValue}${SpecialKey.HIDDEN_SUFFIX}"
                graph.addNode(childKey, childType, "")
                graph.addValueToLink(parentKey, childKey, -1)
                parentKey = childKey
            }
        }

        for (layer in SankeyLayer.entries) {
            for (key in nodesByType[layer].orEmpty()) {
                val nodeHasParent = hasParent(graph, key)
                val nodeHasChild = graph[key]?.hasChild == true
                if (!nodeHasParent && typeHasParent[layer] == true) addHiddenParentChain(key, layer)
                if (!nodeHasChild && typeHasChild[layer] == true) addHiddenChildChain(key, layer)
            }
        }
    }

    fun filterGraphByLayers(graph: SankeyGraph, from: SankeyLayer, to: SankeyLayer) {
        for (key in graph.keys) {
            val index = graph[key]?.type?.ordinal ?: continue
            if (index < from.ordinal || index > to.ordinal) graph.delete(key)
        }
    }

    // Conversion

    fun convertToSankeyData(graph: SankeyGraph, showPercentages: Boolean = false): SankeyData {
        val keys = graph.keys
        val indexByKey = keys.withIndex().associate { it.value to it.index }
        val nodes = keys.map { key ->
            val node = graph[key]
            SankeyData.Node(key, node?.name ?: key, node?.percentageLabel ?: "", node?.type ?: SankeyLayer.Category, node?.isNegative ?: false)
        }
        val links = mutableListOf<SankeyData.Link>()
        for ((sourceIndex, key) in keys.withIndex()) {
            for ((to, value) in graph.links(key)) {
                // Upstream emits dangling links (index -1) after layer trimming;
                // we drop them so the view never sees bad indices.
                val targetIndex = indexByKey[to] ?: continue
                links += SankeyData.Link(sourceIndex, targetIndex, value)
            }
        }
        return SankeyData(nodes, links, showPercentages)
    }

    // Date helpers

    private fun monthLabel(date: LocalDate): String = "%04d-%02d".format(date.year, date.monthValue)

    private fun localizedMonthLabel(label: String, locale: Locale): String {
        val parts = label.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size != 2) return label
        val date = runCatching { LocalDate.of(parts[0], parts[1], 1) }.getOrNull() ?: return label
        return date.format(DateTimeFormatter.ofPattern("MMM yyyy", locale))
    }

    private fun shiftMonth(label: String, months: Long): String {
        val parts = label.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size != 2) return label
        val date = runCatching { LocalDate.of(parts[0], parts[1], 1) }.getOrNull() ?: return label
        return monthLabel(date.plusMonths(months))
    }

    private fun monthsInRange(start: LocalDate, end: LocalDate): Set<Int> {
        val months = HashSet<Int>()
        var cursor = start.monthStart()
        val endMonth = end.monthStart()
        while (cursor <= endMonth) {
            months += cursor.year * 100 + cursor.monthValue
            cursor = cursor.plusMonths(1)
        }
        return months
    }
}
