package io.github.traushb.actuali.services.rules

import io.github.traushb.actuali.models.Rule
import io.github.traushb.actuali.services.Logger

private val logger = Logger("RuleRanker")

/**
 * Port of loot-core `rankRules` (server/rules/rule-utils.ts). Rules run
 * `pre` → default → `post`, and within a stage in *ascending* score order so
 * the most specific rule applies last and wins. Ties break on id, so every
 * client orders an identical rule set identically.
 */
object RuleRanker {
    private val opScores: Map<String, Int> = mapOf(
        "is" to 10, "isNot" to 10,
        "oneOf" to 9, "notOneOf" to 9,
        "isapprox" to 5, "isbetween" to 5,
        "gt" to 1, "gte" to 1, "lt" to 1, "lte" to 1,
        "contains" to 0, "doesNotContain" to 0, "matches" to 0,
        "hasTags" to 0, "hasAnyTag" to 0,
        "onBudget" to 0, "offBudget" to 0,
    )

    private val doublingOps = setOf("is", "isNot", "isapprox", "oneOf", "notOneOf")

    fun score(rule: Rule): Int {
        var total = 0
        for (condition in rule.conditions) {
            val score = opScores[condition.op]
            if (score == null) {
                // Upstream's reducer returns 0 for the unknown op, which resets
                // the running total but keeps accumulating later conditions.
                logger.debug("Found invalid operation while ranking: ${condition.op}")
                total = 0
                continue
            }
            total += score
        }
        // A rule made purely of exact-match conditions is twice as specific.
        if (rule.conditions.isNotEmpty() && rule.conditions.all { it.op in doublingOps }) {
            return total * 2
        }
        return total
    }

    fun rank(rules: List<Rule>): List<Rule> {
        // Score once per rule, not once per comparison.
        val scores = HashMap<String, Int>()
        for (rule in rules) scores.getOrPut(rule.id) { score(rule) }

        return Rule.Stage.entries.flatMap { stage ->
            rules.filter { it.stage == stage }.sortedWith { lhs, rhs ->
                val left = scores[lhs.id] ?: 0
                val right = scores[rhs.id] ?: 0
                if (left == right) lhs.id.compareTo(rhs.id) else left.compareTo(right)
            }
        }
    }
}
