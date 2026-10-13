package io.github.traushb.actuali.models

import io.github.traushb.actuali.services.deposits.DepositGrowth
import io.github.traushb.actuali.services.schedules.DayDate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.max
import kotlin.math.min

/**
 * Synced configuration for an interest-earning deposit account, persisted in
 * Actual's `preferences` table under `actuali:deposit:<accountId>`. Covers a
 * fixed deposit (one lump sum for a term) and a recurring deposit (a fixed
 * amount every month for a term), both compounding at a stated frequency.
 */
@Serializable(with = DepositConfigSerializer::class)
data class DepositConfig(
    val kind: Kind,
    /** Cents: the lump sum for a fixed deposit, the monthly instalment for a recurring one. */
    val amount: Long,
    /** Nominal annual rate as a percentage — 7.1 means 7.1%. */
    val annualRatePercent: Double,
    val compounding: Compounding,
    /** When the deposit was opened; every figure is measured from here. */
    val openedOn: DayDate,
    /** How long the deposit runs, in months. */
    val termMonths: Int,
) {
    enum class Kind(val rawValue: String) {
        /** One lump sum, left for the term. */
        Fixed("fixed"),

        /** A fixed amount deposited every month for the term. */
        Recurring("recurring"),
        ;

        companion object {
            fun fromRaw(raw: String?): Kind? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    /**
     * How often interest is credited. The raw value is compounds per year,
     * and every case divides 12 evenly so `periodMonths` is exact.
     */
    enum class Compounding(val rawValue: Int) {
        Annually(1),
        SemiAnnually(2),
        Quarterly(4),
        Monthly(12),
        ;

        val periodMonths: Int get() = 12 / rawValue

        companion object {
            fun fromRaw(raw: Int?): Compounding? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    /** The day the deposit matures. */
    val maturityDate: DayDate get() = openedOn.addingMonths(max(0, termMonths))

    /** Whole months from opening to [date], floored at zero. */
    fun monthsElapsed(date: DayDate = DayDate.today()): Int {
        if (openedOn >= date) return 0
        var months = (date.year - openedOn.year) * 12 + (date.month - openedOn.month)
        // Part-way through a month doesn't count: interest credits on whole periods.
        if (date.day < openedOn.day) months -= 1
        return max(0, months)
    }

    fun monthsRemaining(date: DayDate = DayDate.today()): Int = max(0, termMonths - monthsElapsed(date))

    fun hasMatured(date: DayDate = DayDate.today()): Boolean = monthsElapsed(date) >= termMonths

    /** Share of the term completed, 0...1. A zero-month term is already over. */
    fun fractionElapsed(date: DayDate = DayDate.today()): Double =
        if (termMonths <= 0) 1.0 else min(1.0, monthsElapsed(date).toDouble() / termMonths)

    /** What the deposit is worth on [date], in cents. */
    fun value(date: DayDate = DayDate.today()): Long = DepositGrowth.value(this, monthsElapsed(date))

    /** What it pays out at the end of the term. */
    val maturityValue: Long get() = DepositGrowth.value(this, termMonths)

    /** What has actually gone in by [date]. */
    fun deposited(date: DayDate = DayDate.today()): Long = DepositGrowth.deposited(this, monthsElapsed(date))

    /** Interest credited so far. */
    fun interestEarned(date: DayDate = DayDate.today()): Long = value(date) - deposited(date)

    /** Total interest the deposit pays over its whole term. */
    val totalInterest: Long get() = maturityValue - DepositGrowth.deposited(this, termMonths)
}

/**
 * `openedOn` crosses the wire as a `YYYYMMDD` integer — Actual's own date
 * convention. The compounding frequency falls back to quarterly when missing
 * or unrecognised (a newer client's value), so losing it costs precision
 * rather than the account's tracking. A missing or impossible opening date
 * is not recoverable that way, so that does fail.
 */
object DepositConfigSerializer : KSerializer<DepositConfig> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): DepositConfig {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        fun required(key: String) = json[key]?.jsonPrimitive ?: throw IllegalArgumentException("$key required")
        return DepositConfig(
            kind = DepositConfig.Kind.fromRaw(required("kind").content) ?: throw IllegalArgumentException("kind"),
            amount = required("amount").longOrNull ?: throw IllegalArgumentException("amount"),
            annualRatePercent = required("annualRatePercent").doubleOrNull ?: throw IllegalArgumentException("annualRatePercent"),
            compounding = DepositConfig.Compounding.fromRaw(json["compounding"]?.jsonPrimitive?.intOrNull) ?: DepositConfig.Compounding.Quarterly,
            openedOn = required("openedOn").intOrNull?.let(DayDate::fromYYYYMMDD) ?: throw IllegalArgumentException("openedOn must be a valid YYYYMMDD date"),
            termMonths = required("termMonths").intOrNull ?: throw IllegalArgumentException("termMonths"),
        )
    }

    override fun serialize(encoder: Encoder, value: DepositConfig) {
        encoder.encodeSerializableValue(
            JsonObject.serializer(),
            JsonObject(
                linkedMapOf(
                    "kind" to JsonPrimitive(value.kind.rawValue),
                    "amount" to JsonPrimitive(value.amount),
                    "annualRatePercent" to JsonPrimitive(value.annualRatePercent),
                    "compounding" to JsonPrimitive(value.compounding.rawValue),
                    "openedOn" to JsonPrimitive(value.openedOn.yyyymmdd),
                    "termMonths" to JsonPrimitive(value.termMonths),
                ),
            ),
        )
    }
}
