package io.github.traushb.actuali.services.reports.engines

import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.models.roundedHalfAwayFromZero
import io.github.traushb.actuali.services.reports.ConditionsFilter
import io.github.traushb.actuali.services.reports.FormulaMeta
import io.github.traushb.actuali.services.reports.TimeFrame
import io.github.traushb.actuali.services.reports.ymd
import java.text.Collator
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/**
 * Native evaluator for formula-card widgets. Upstream runs formulas through
 * HyperFormula (a full spreadsheet engine); we support the basic subset used
 * by common cards — numbers, strings, query("name"), IF, ABS, ROUND,
 * comparisons, arithmetic, and concatenation — and surface anything else as
 * [Result.Unsupported] so the card explains itself instead of guessing.
 */
object FormulaEngine {
    sealed interface Result {
        /** Currency units (upstream integerToAmount: cents / 100). */
        data class Value(val value: Double) : Result
        data class Text(val value: String) : Result
        data class Unsupported(val reason: String) : Result
    }

    fun compute(meta: FormulaMeta?, transactions: List<Transaction>, today: LocalDate, context: ConditionsFilter.Context): Result {
        val formula = meta?.formula
        if (formula == null || !formula.startsWith("=")) return Result.Unsupported("Formula must start with =")
        val expr = Parser(formula.drop(1)).parseExpression() ?: return Result.Unsupported("This formula isn't supported yet")
        return try {
            when (val result = evaluate(expr) { name -> querySum(name, meta, transactions, today, context) }) {
                is Value.Num -> if (result.value.isFinite()) Result.Value(result.value) else Result.Unsupported("This formula isn't supported yet")
                is Value.Str -> Result.Text(result.value)
                is Value.Bool -> Result.Text(if (result.value) "TRUE" else "FALSE")
            }
        } catch (e: EvalError) {
            Result.Unsupported(if (e == EvalError.DivisionByZero) "Division by zero" else "This formula isn't supported yet")
        }
    }

    /**
     * Sum (currency units) of transactions matching the named query's
     * conditions and time frame. Unknown names are 0, like upstream.
     */
    private fun querySum(name: String, meta: FormulaMeta?, transactions: List<Transaction>, today: LocalDate, context: ConditionsFilter.Context): Double {
        val query = meta?.queries?.get(name) ?: return 0.0
        var pool = transactions.filter { !it.tombstone }
        // Upstream only date-filters when the timeFrame has a mode.
        val timeFrame = query.timeFrame
        if (timeFrame?.mode != null) {
            val (start, end) = TimeFrame.resolve(timeFrame, today)
            pool = pool.filter { it.date in start.ymd..end.ymd }
        }
        return pool.filter { ConditionsFilter.matches(it, query.conditions, query.conditionsOp, context) }.sumOf { it.amount } / 100.0
    }

    sealed interface Expr {
        data class Number(val value: Double) : Expr
        data class Str(val value: String) : Expr
        data class Function(val name: String, val args: List<Expr>) : Expr
        data class Add(val left: Expr, val right: Expr) : Expr
        data class Sub(val left: Expr, val right: Expr) : Expr
        data class Mul(val left: Expr, val right: Expr) : Expr
        data class Div(val left: Expr, val right: Expr) : Expr
        data class Neg(val expr: Expr) : Expr
        data class Compare(val op: String, val left: Expr, val right: Expr) : Expr
        data class Concat(val left: Expr, val right: Expr) : Expr
    }

    private sealed interface Value {
        data class Num(val value: Double) : Value
        data class Str(val value: String) : Value
        data class Bool(val value: Boolean) : Value
    }

    private class EvalError private constructor() : Exception() {
        companion object {
            val DivisionByZero = EvalError()
            val InvalidArguments = EvalError()
            val InvalidType = EvalError()
        }
    }

    private fun evaluate(expr: Expr, query: (String) -> Double): Value = when (expr) {
        is Expr.Number -> Value.Num(expr.value)
        is Expr.Str -> Value.Str(expr.value)
        is Expr.Function -> evaluateFunction(expr.name, expr.args, query)
        is Expr.Add -> Value.Num(number(expr.left, query) + number(expr.right, query))
        is Expr.Sub -> Value.Num(number(expr.left, query) - number(expr.right, query))
        is Expr.Mul -> Value.Num(number(expr.left, query) * number(expr.right, query))
        is Expr.Div -> {
            val divisor = number(expr.right, query)
            if (abs(divisor) <= Math.ulp(1.0)) throw EvalError.DivisionByZero
            Value.Num(number(expr.left, query) / divisor)
        }
        is Expr.Neg -> Value.Num(-number(expr.expr, query))
        is Expr.Compare -> Value.Bool(compare(expr.op, evaluate(expr.left, query), evaluate(expr.right, query)))
        is Expr.Concat -> Value.Str(stringValue(evaluate(expr.left, query)) + stringValue(evaluate(expr.right, query)))
    }

    private fun number(expr: Expr, query: (String) -> Double): Double =
        (evaluate(expr, query) as? Value.Num)?.value ?: throw EvalError.InvalidType

    private fun evaluateFunction(name: String, args: List<Expr>, query: (String) -> Double): Value = when (name) {
        "query" -> {
            val queryName = (args.singleOrNull()?.let { evaluate(it, query) } as? Value.Str)?.value ?: throw EvalError.InvalidArguments
            Value.Num(query(queryName))
        }
        "if" -> {
            if (args.size != 3) throw EvalError.InvalidArguments
            evaluate(if (isTruthy(evaluate(args[0], query))) args[1] else args[2], query)
        }
        "abs" -> {
            if (args.size != 1) throw EvalError.InvalidArguments
            Value.Num(abs(number(args[0], query)))
        }
        "round" -> {
            if (args.size != 1 && args.size != 2) throw EvalError.InvalidArguments
            val value = number(args[0], query)
            val digits = if (args.size == 2) number(args[1], query) else 0.0
            val factor = 10.0.pow(digits)
            val result = roundedHalfAwayFromZero(value * factor) / factor
            if (!result.isFinite()) throw EvalError.InvalidType
            Value.Num(result)
        }
        else -> throw EvalError.InvalidArguments
    }

    private fun isTruthy(value: Value): Boolean = when (value) {
        is Value.Num -> value.value != 0.0
        is Value.Str -> when (value.value.uppercase()) {
            "TRUE" -> true
            "FALSE", "" -> false
            else -> throw EvalError.InvalidType
        }
        is Value.Bool -> value.value
    }

    /** Case- and accent-insensitive, like Foundation's compare with those options. */
    private val textCollator: Collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.PRIMARY }

    private fun compare(op: String, left: Value, right: Value): Boolean {
        if (left is Value.Num && right is Value.Num) return apply(op, left.value, right.value)
        if (left is Value.Str && right is Value.Str) {
            val result = textCollator.compare(left.value, right.value)
            return when (op) {
                "=" -> result == 0
                "<>" -> result != 0
                ">" -> result > 0
                "<" -> result < 0
                ">=" -> result >= 0
                "<=" -> result <= 0
                else -> throw EvalError.InvalidArguments
            }
        }
        return apply(op, stringValue(left), stringValue(right))
    }

    private fun <T : Comparable<T>> apply(op: String, left: T, right: T): Boolean = when (op) {
        "=" -> left == right
        "<>" -> left != right
        ">" -> left > right
        "<" -> left < right
        ">=" -> left >= right
        "<=" -> left <= right
        else -> throw EvalError.InvalidArguments
    }

    private fun stringValue(value: Value): String = when (value) {
        is Value.Str -> value.value
        is Value.Bool -> if (value.value) "TRUE" else "FALSE"
        is Value.Num -> {
            if (!value.value.isFinite()) {
                ""
            } else {
                val result = String.format(Locale.ROOT, "%.12f", value.value).trimEnd('0').removeSuffix(".")
                if (result == "-0") "0" else result
            }
        }
    }

    // Recursive-descent parser
    //
    //   expression    := concatenation (comparison-op concatenation)*
    //   concatenation := arithmetic ("&" arithmetic)*
    //   arithmetic    := term (("+" | "-") term)*
    //   term          := factor (("*" | "/") factor)*
    //   factor        := NUMBER | STRING | "-" factor | "(" expression ")" | call

    class Parser(private val chars: String) {
        private var pos = 0

        fun parseExpression(): Expr? = expression()?.takeIf { atEnd() }

        private fun expression(): Expr? {
            var left = concatenation() ?: return null
            while (true) {
                val op = comparisonOperator() ?: break
                left = Expr.Compare(op, left, concatenation() ?: return null)
            }
            return left
        }

        private fun concatenation(): Expr? {
            var left = arithmetic() ?: return null
            while (consume('&')) left = Expr.Concat(left, arithmetic() ?: return null)
            return left
        }

        private fun arithmetic(): Expr? {
            var left = term() ?: return null
            while (true) {
                val op = peekOperator("+-") ?: break
                pos += 1
                val right = term() ?: return null
                left = if (op == '+') Expr.Add(left, right) else Expr.Sub(left, right)
            }
            return left
        }

        private fun term(): Expr? {
            var left = factor() ?: return null
            while (true) {
                val op = peekOperator("*/") ?: break
                pos += 1
                val right = factor() ?: return null
                left = if (op == '*') Expr.Mul(left, right) else Expr.Div(left, right)
            }
            return left
        }

        private fun factor(): Expr? {
            skipWhitespace()
            val c = peek() ?: return null
            return when {
                c == '-' -> {
                    pos += 1
                    factor()?.let(Expr::Neg)
                }
                c == '(' -> {
                    pos += 1
                    expression()?.takeIf { consume(')') }
                }
                c == '"' -> string()
                c.isDigit() || c == '.' -> number()
                c.isLetter() -> functionCall()
                else -> null
            }
        }

        private fun number(): Expr? {
            skipWhitespace()
            val start = pos
            while (peek()?.let { it.isDigit() || it == '.' } == true) pos += 1
            return chars.substring(start, pos).toDoubleOrNull()?.let(Expr::Number)
        }

        private fun functionCall(): Expr? {
            skipWhitespace()
            val start = pos
            while (peek()?.let { it.isLetterOrDigit() || it == '_' } == true) pos += 1
            val ident = chars.substring(start, pos).lowercase()
            if (!consume('(')) return null
            val args = mutableListOf<Expr>()
            if (consume(')')) return Expr.Function(ident, args)
            while (true) {
                args += expression() ?: return null
                if (consume(')')) break
                if (!consume(',')) return null
            }
            return Expr.Function(ident, args)
        }

        private fun string(): Expr? {
            if (!consume('"')) return null
            val start = pos
            while (peek()?.let { it != '"' } == true) pos += 1
            val value = chars.substring(start, pos)
            return if (consume('"')) Expr.Str(value) else null
        }

        private fun peek(): Char? = chars.getOrNull(pos)

        private fun skipWhitespace() {
            while (peek()?.isWhitespace() == true) pos += 1
        }

        private fun peekOperator(ops: String): Char? {
            skipWhitespace()
            return peek()?.takeIf { it in ops }
        }

        private fun comparisonOperator(): String? {
            skipWhitespace()
            for (op in listOf(">=", "<=", "<>", "=", ">", "<")) {
                if (chars.startsWith(op, pos)) {
                    pos += op.length
                    return op
                }
            }
            return null
        }

        private fun consume(character: Char): Boolean {
            skipWhitespace()
            if (peek() != character) return false
            pos += 1
            return true
        }

        private fun atEnd(): Boolean {
            skipWhitespace()
            return pos >= chars.length
        }
    }
}
