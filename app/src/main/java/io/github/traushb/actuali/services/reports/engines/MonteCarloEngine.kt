package io.github.traushb.actuali.services.reports.engines

import io.github.traushb.actuali.models.roundedHalfAwayFromZero
import io.github.traushb.actuali.services.numberOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Widget meta (upstream loot-core dashboard.ts, MonteCarloWidget)

@Serializable
data class MonteCarloPotMeta(
    val id: String? = null,
    val name: String? = null,
    /** Starting balance in minor units (cents). */
    val startingBalance: Double? = null,
    /** One of "equity-100" | "equity-80" | "equity-60" | "equity-40" | "cash" | "custom". */
    val allocationPreset: String? = null,
    val expectedReturnMean: Double? = null,
    val returnStdDev: Double? = null,
    /** Age from which the pot can fund withdrawals; null = immediately. */
    val accessAge: Double? = null,
    /** Account whose live balance supplies the starting balance; null = manual. */
    val accountId: String? = null,
    val withdrawalTaxRate: Double? = null,
    val taxableFraction: Double? = null,
    val annualFeeFixed: Double? = null,
    val feeAdjustsWithInflation: Boolean? = null,
    val annualFeeRate: Double? = null,
)

@Serializable
data class MonteCarloWithdrawalRuleMeta(
    /** One of "none" | "guardrails" | "ratcheting" | "floor-ceiling" | "boundaries". */
    val type: String? = null,
    val prosperityTriggerPct: Double? = null,
    val prosperityIncreasePct: Double? = null,
    val preservationTriggerPct: Double? = null,
    val preservationCutPct: Double? = null,
    val balanceThresholdMultiple: Double? = null,
    val consecutiveYears: Double? = null,
    val ratchetIncreasePct: Double? = null,
    val floorPct: Double? = null,
    val ceilingPct: Double? = null,
    val upperRateThreshold: Double? = null,
    val upperCutPct: Double? = null,
    val lowerRateThreshold: Double? = null,
    val lowerIncreasePct: Double? = null,
)

@Serializable
data class MonteCarloSpendingPhaseMeta(
    val id: String? = null,
    val name: String? = null,
    /** Age the phase begins (inclusive); null = starts immediately. */
    val fromAge: Double? = null,
    /** Yearly spending in minor units, in today's money. */
    val annualWithdrawal: Double? = null,
)

@Serializable
data class MonteCarloTaxBandMeta(
    val id: String? = null,
    /** Annual taxable income threshold in minor units, today's money. */
    val from: Double? = null,
    /** Decimal fraction (0.2 = 20%). */
    val rate: Double? = null,
)

@Serializable
data class MonteCarloMeta(
    val name: String? = null,
    val pots: List<MonteCarloPotMeta>? = null,
    val withdrawalStrategy: String? = null,
    val returnModel: String? = null,
    val withdrawalRule: MonteCarloWithdrawalRuleMeta? = null,
    val minimumWithdrawal: Double? = null,
    val spendingPhases: List<MonteCarloSpendingPhaseMeta>? = null,
    /**
     * Upstream distinguishes absent (use the default) from an explicit JSON
     * null (flat withdrawals): Kotlin null = absent, [JsonNull] = null, else
     * the number.
     */
    @Serializable(with = ExplicitNullSerializer::class)
    val inflationMean: JsonElement? = null,
    val inflationStdDev: Double? = null,
    val taxModel: String? = null,
    val taxBands: List<MonteCarloTaxBandMeta>? = null,
    val currentAge: Double? = null,
    val targetAge: Double? = null,
    val simulationCount: Double? = null,
) {
    init {
        // A non-numeric value fails the meta as a whole, as a typed decode would.
        require(inflationMean == null || inflationMean is JsonNull || inflationMean.numberOrNull != null) { "inflationMean must be a number or null" }
    }
}

/**
 * Keeps an explicit JSON null as [JsonNull] instead of collapsing it into
 * "absent": the nullable descriptor makes the decoder hand nulls to us.
 */
object ExplicitNullSerializer : KSerializer<JsonElement?> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor.nullable

    override fun deserialize(decoder: Decoder): JsonElement = (decoder as JsonDecoder).decodeJsonElement()

    override fun serialize(encoder: Encoder, value: JsonElement?) = encoder.encodeSerializableValue(JsonElement.serializer(), value ?: JsonNull)
}

// Resolved configuration (upstream monteCarloSimulation.ts)

enum class MonteCarloWithdrawalStrategy(val rawValue: String) {
    Proportional("proportional"),
    Sequential("sequential"),
    BestPerformer("best-performer"),
    TargetMix("target-mix"),
}

enum class MonteCarloReturnModel(val rawValue: String) {
    Normal("normal"),
    HistoricalBootstrap("historical-bootstrap"),
    HistoricalSequence("historical-sequence"),
}

enum class MonteCarloTaxModel(val rawValue: String) {
    Flat("flat"),
    Bands("bands"),
}

enum class MonteCarloWithdrawalRuleType(val rawValue: String) {
    None("none"),
    Guardrails("guardrails"),
    Ratcheting("ratcheting"),
    FloorCeiling("floor-ceiling"),
    Boundaries("boundaries"),
}

enum class MonteCarloAllocationPreset(val rawValue: String) {
    Equity100("equity-100"),
    Equity80("equity-80"),
    Equity60("equity-60"),
    Equity40("equity-40"),
    Cash("cash"),
    Custom("custom"),
    ;

    /** Asset mix (stocks, bonds, cash) behind each preset, used by the historical return models (upstream PRESET_ASSET_WEIGHTS). */
    val assetWeights: Triple<Double, Double, Double>?
        get() = when (this) {
            Equity100 -> Triple(1.0, 0.0, 0.0)
            Equity80 -> Triple(0.8, 0.2, 0.0)
            Equity60 -> Triple(0.6, 0.4, 0.0)
            Equity40 -> Triple(0.4, 0.6, 0.0)
            Cash -> Triple(0.0, 0.0, 1.0)
            Custom -> null
        }
}

private inline fun <reified T : Enum<T>> rawEnum(raw: String?, rawValue: (T) -> String): T? =
    enumValues<T>().firstOrNull { rawValue(it) == raw }

/** One invested pot; defaults mirror upstream createMonteCarloPot. */
data class MonteCarloPot(
    val id: String,
    val name: String = "",
    val startingBalance: Double = 50_000_000.0,
    val allocationPreset: MonteCarloAllocationPreset = MonteCarloAllocationPreset.Equity60,
    val expectedReturnMean: Double = 0.06,
    val returnStdDev: Double = 0.1,
    val accessAge: Double? = null,
    val accountId: String? = null,
    val withdrawalTaxRate: Double = 0.0,
    val taxableFraction: Double = 1.0,
    val annualFeeFixed: Double = 0.0,
    val feeAdjustsWithInflation: Boolean = false,
    val annualFeeRate: Double = 0.0,
)

/** Fully-populated rule settings; defaults mirror upstream WITHDRAWAL_RULE_DEFAULTS. */
data class MonteCarloWithdrawalRule(
    val type: MonteCarloWithdrawalRuleType = MonteCarloWithdrawalRuleType.None,
    // Guardrails (Guyton-Klinger)
    val prosperityTriggerPct: Double = 0.2,
    val prosperityIncreasePct: Double = 0.1,
    val preservationTriggerPct: Double = 0.2,
    val preservationCutPct: Double = 0.1,
    // Ratcheting (Kitces)
    val balanceThresholdMultiple: Double = 1.5,
    val consecutiveYears: Double = 3.0,
    val ratchetIncreasePct: Double = 0.05,
    // Floor & ceiling (Bengen)
    val floorPct: Double = 0.15,
    val ceilingPct: Double = 0.2,
    // Boundaries
    val upperRateThreshold: Double = 0.06,
    val upperCutPct: Double = 0.1,
    val lowerRateThreshold: Double = 0.04,
    val lowerIncreasePct: Double = 0.05,
)

data class MonteCarloSpendingPhase(val id: String, val name: String = "", val fromAge: Double? = null, val annualWithdrawal: Double = 2_000_000.0)

data class MonteCarloTaxBand(val id: String, val from: Double = 0.0, val rate: Double = 0.0)

/** Defaults mirror upstream MONTE_CARLO_DEFAULTS. */
data class MonteCarloConfig(
    val pots: List<MonteCarloPot> = listOf(MonteCarloPot("pot-1")),
    val withdrawalStrategy: MonteCarloWithdrawalStrategy = MonteCarloWithdrawalStrategy.Proportional,
    val returnModel: MonteCarloReturnModel = MonteCarloReturnModel.Normal,
    val withdrawalRule: MonteCarloWithdrawalRule = MonteCarloWithdrawalRule(),
    val minimumWithdrawal: Double = 0.0,
    val spendingPhases: List<MonteCarloSpendingPhase> = listOf(MonteCarloSpendingPhase("phase-1")),
    /** null = flat withdrawals (upstream's explicit null). */
    val inflationMean: Double? = 0.025,
    val inflationStdDev: Double = 0.02,
    val taxModel: MonteCarloTaxModel = MonteCarloTaxModel.Flat,
    val taxBands: List<MonteCarloTaxBand> = listOf(MonteCarloTaxBand("band-1")),
    val currentAge: Double = 60.0,
    val targetAge: Double = 90.0,
    val simulationCount: Double = 5000.0,
)

// Historical returns (upstream monteCarloHistoricalReturns.ts)

/**
 * Historical annual nominal US returns (Damodaran, NYU Stern, 1928-2025):
 * stocks = S&P 500 total return, bonds = 10y Treasury, cash = 3m T-bill.
 */
data class MonteCarloHistoricalReturn(val year: Int, val stocks: Double, val bonds: Double, val cash: Double) {
    companion object {
        val all = listOf(
            MonteCarloHistoricalReturn(1928, 0.4381, 0.0084, 0.0308),
            MonteCarloHistoricalReturn(1929, -0.083, 0.042, 0.0316),
            MonteCarloHistoricalReturn(1930, -0.2512, 0.0454, 0.0455),
            MonteCarloHistoricalReturn(1931, -0.4384, -0.0256, 0.0231),
            MonteCarloHistoricalReturn(1932, -0.0864, 0.0879, 0.0107),
            MonteCarloHistoricalReturn(1933, 0.4998, 0.0186, 0.0096),
            MonteCarloHistoricalReturn(1934, -0.0119, 0.0796, 0.0028),
            MonteCarloHistoricalReturn(1935, 0.4674, 0.0447, 0.0017),
            MonteCarloHistoricalReturn(1936, 0.3194, 0.0502, 0.0017),
            MonteCarloHistoricalReturn(1937, -0.3534, 0.0138, 0.0028),
            MonteCarloHistoricalReturn(1938, 0.2928, 0.0421, 0.0007),
            MonteCarloHistoricalReturn(1939, -0.011, 0.0441, 0.0005),
            MonteCarloHistoricalReturn(1940, -0.1067, 0.054, 0.0004),
            MonteCarloHistoricalReturn(1941, -0.1277, -0.0202, 0.0013),
            MonteCarloHistoricalReturn(1942, 0.1917, 0.0229, 0.0034),
            MonteCarloHistoricalReturn(1943, 0.2506, 0.0249, 0.0038),
            MonteCarloHistoricalReturn(1944, 0.1903, 0.0258, 0.0038),
            MonteCarloHistoricalReturn(1945, 0.3582, 0.038, 0.0038),
            MonteCarloHistoricalReturn(1946, -0.0843, 0.0313, 0.0038),
            MonteCarloHistoricalReturn(1947, 0.052, 0.0092, 0.006),
            MonteCarloHistoricalReturn(1948, 0.057, 0.0195, 0.0105),
            MonteCarloHistoricalReturn(1949, 0.183, 0.0466, 0.0112),
            MonteCarloHistoricalReturn(1950, 0.3081, 0.0043, 0.012),
            MonteCarloHistoricalReturn(1951, 0.2368, -0.003, 0.0152),
            MonteCarloHistoricalReturn(1952, 0.1815, 0.0227, 0.0172),
            MonteCarloHistoricalReturn(1953, -0.0121, 0.0414, 0.0189),
            MonteCarloHistoricalReturn(1954, 0.5256, 0.0329, 0.0094),
            MonteCarloHistoricalReturn(1955, 0.326, -0.0134, 0.0172),
            MonteCarloHistoricalReturn(1956, 0.0744, -0.0226, 0.0262),
            MonteCarloHistoricalReturn(1957, -0.1046, 0.068, 0.0322),
            MonteCarloHistoricalReturn(1958, 0.4372, -0.021, 0.0177),
            MonteCarloHistoricalReturn(1959, 0.1206, -0.0265, 0.0339),
            MonteCarloHistoricalReturn(1960, 0.0034, 0.1164, 0.0287),
            MonteCarloHistoricalReturn(1961, 0.2664, 0.0206, 0.0235),
            MonteCarloHistoricalReturn(1962, -0.0881, 0.0569, 0.0277),
            MonteCarloHistoricalReturn(1963, 0.2261, 0.0168, 0.0316),
            MonteCarloHistoricalReturn(1964, 0.1642, 0.0373, 0.0355),
            MonteCarloHistoricalReturn(1965, 0.124, 0.0072, 0.0395),
            MonteCarloHistoricalReturn(1966, -0.0997, 0.0291, 0.0486),
            MonteCarloHistoricalReturn(1967, 0.238, -0.0158, 0.0429),
            MonteCarloHistoricalReturn(1968, 0.1081, 0.0327, 0.0534),
            MonteCarloHistoricalReturn(1969, -0.0824, -0.0501, 0.0667),
            MonteCarloHistoricalReturn(1970, 0.0356, 0.1675, 0.0639),
            MonteCarloHistoricalReturn(1971, 0.1422, 0.0979, 0.0433),
            MonteCarloHistoricalReturn(1972, 0.1876, 0.0282, 0.0406),
            MonteCarloHistoricalReturn(1973, -0.1431, 0.0366, 0.0704),
            MonteCarloHistoricalReturn(1974, -0.259, 0.0199, 0.0785),
            MonteCarloHistoricalReturn(1975, 0.37, 0.0361, 0.0579),
            MonteCarloHistoricalReturn(1976, 0.2383, 0.1598, 0.0498),
            MonteCarloHistoricalReturn(1977, -0.0698, 0.0129, 0.0526),
            MonteCarloHistoricalReturn(1978, 0.0651, -0.0078, 0.0718),
            MonteCarloHistoricalReturn(1979, 0.1852, 0.0067, 0.1005),
            MonteCarloHistoricalReturn(1980, 0.3174, -0.0299, 0.1139),
            MonteCarloHistoricalReturn(1981, -0.047, 0.082, 0.1404),
            MonteCarloHistoricalReturn(1982, 0.2042, 0.3281, 0.1109),
            MonteCarloHistoricalReturn(1983, 0.2234, 0.032, 0.0895),
            MonteCarloHistoricalReturn(1984, 0.0615, 0.1373, 0.0992),
            MonteCarloHistoricalReturn(1985, 0.3124, 0.2571, 0.0772),
            MonteCarloHistoricalReturn(1986, 0.1849, 0.2428, 0.0615),
            MonteCarloHistoricalReturn(1987, 0.0581, -0.0496, 0.0596),
            MonteCarloHistoricalReturn(1988, 0.1654, 0.0822, 0.0689),
            MonteCarloHistoricalReturn(1989, 0.3148, 0.1769, 0.0839),
            MonteCarloHistoricalReturn(1990, -0.0306, 0.0624, 0.0775),
            MonteCarloHistoricalReturn(1991, 0.3023, 0.15, 0.0554),
            MonteCarloHistoricalReturn(1992, 0.0749, 0.0936, 0.0351),
            MonteCarloHistoricalReturn(1993, 0.0997, 0.1421, 0.0307),
            MonteCarloHistoricalReturn(1994, 0.0133, -0.0804, 0.0437),
            MonteCarloHistoricalReturn(1995, 0.372, 0.2348, 0.0566),
            MonteCarloHistoricalReturn(1996, 0.2268, 0.0143, 0.0515),
            MonteCarloHistoricalReturn(1997, 0.331, 0.0994, 0.052),
            MonteCarloHistoricalReturn(1998, 0.2834, 0.1492, 0.0491),
            MonteCarloHistoricalReturn(1999, 0.2089, -0.0825, 0.0478),
            MonteCarloHistoricalReturn(2000, -0.0903, 0.1666, 0.06),
            MonteCarloHistoricalReturn(2001, -0.1185, 0.0557, 0.0348),
            MonteCarloHistoricalReturn(2002, -0.2197, 0.1512, 0.0164),
            MonteCarloHistoricalReturn(2003, 0.2836, 0.0038, 0.0103),
            MonteCarloHistoricalReturn(2004, 0.1074, 0.0449, 0.014),
            MonteCarloHistoricalReturn(2005, 0.0483, 0.0287, 0.0322),
            MonteCarloHistoricalReturn(2006, 0.1561, 0.0196, 0.0485),
            MonteCarloHistoricalReturn(2007, 0.0548, 0.1021, 0.0448),
            MonteCarloHistoricalReturn(2008, -0.3655, 0.201, 0.014),
            MonteCarloHistoricalReturn(2009, 0.2594, -0.1112, 0.0015),
            MonteCarloHistoricalReturn(2010, 0.1482, 0.0846, 0.0014),
            MonteCarloHistoricalReturn(2011, 0.021, 0.1604, 0.0005),
            MonteCarloHistoricalReturn(2012, 0.1589, 0.0297, 0.0009),
            MonteCarloHistoricalReturn(2013, 0.3215, -0.091, 0.0006),
            MonteCarloHistoricalReturn(2014, 0.1352, 0.1075, 0.0003),
            MonteCarloHistoricalReturn(2015, 0.0138, 0.0128, 0.0005),
            MonteCarloHistoricalReturn(2016, 0.1177, 0.0069, 0.0032),
            MonteCarloHistoricalReturn(2017, 0.2161, 0.028, 0.0095),
            MonteCarloHistoricalReturn(2018, -0.0423, -0.0002, 0.0197),
            MonteCarloHistoricalReturn(2019, 0.3121, 0.0964, 0.0211),
            MonteCarloHistoricalReturn(2020, 0.1802, 0.1133, 0.0036),
            MonteCarloHistoricalReturn(2021, 0.2847, -0.0442, 0.0004),
            MonteCarloHistoricalReturn(2022, -0.1804, -0.1783, 0.0209),
            MonteCarloHistoricalReturn(2023, 0.2606, 0.0388, 0.0528),
            MonteCarloHistoricalReturn(2024, 0.2488, -0.0164, 0.0518),
            MonteCarloHistoricalReturn(2025, 0.1778, 0.078, 0.0421),
        )
    }
}

// Output

data class MonteCarloPercentileBand(
    /** 0 = starting point, 1..horizonYears = end of that year. Values in cents. */
    val year: Int,
    val p5: Long,
    val p10: Long,
    val p25: Long,
    val p30: Long,
    val p50: Long,
    val p70: Long,
    val p75: Long,
    val p90: Long,
)

data class MonteCarloData(
    /** Fraction (0..1) of simulations that survived the full horizon. */
    val successRate: Double,
    val currentAge: Double,
    val horizonYears: Int,
    val simulationCount: Int,
    val percentileBands: List<MonteCarloPercentileBand>,
)

// PRNG

/**
 * Exact port of upstream mulberry32 + Box-Muller normal sampler, so seeded
 * runs reproduce the web app's numbers.
 */
class MonteCarloRandom(seed: UInt) {
    private var state: UInt = seed

    /** Uniform in [0, 1) (upstream mulberry32). */
    fun next(): Double {
        state += 0x6d2b79f5u
        var mixed = (state xor (state shr 15)) * (state or 1u)
        mixed = (mixed + ((mixed xor (mixed shr 7)) * (mixed or 61u))) xor mixed
        return (mixed xor (mixed shr 14)).toDouble() / 4_294_967_296.0
    }

    /**
     * Standard normal draw via the Box-Muller transform (upstream
     * makeNormalSampler; the 1 - next() shift avoids log(0)). StrictMath is
     * fdlibm, the same library V8's Math.log/Math.cos are ported from, so
     * draws match the web app's to the last bit.
     */
    fun nextNormal(): Double {
        val uniform1 = 1 - next()
        val uniform2 = next()
        return StrictMath.sqrt(-2 * StrictMath.log(uniform1)) * StrictMath.cos(2 * PI * uniform2)
    }
}

// Engine

/**
 * Port of the webapp's monteCarloSimulation.ts drawdown engine. Pure and
 * deterministic for a given seed; upstream seeds with 1234 by default so
 * identical inputs always produce identical headline numbers.
 *
 * Pots may reference an account (`accountId`); pass the current balance of
 * those accounts (in cents) via `accountBalances` and the pot's starting
 * balance is replaced with `max(0, balance)`, mirroring upstream
 * useResolvedMonteCarloConfig. Pots without a match keep their stored
 * starting balance.
 */
object MonteCarloEngine {
    /** 1e14 minor units; keeps compounded results formatter-safe (upstream MAX_AMOUNT). */
    const val MAX_AMOUNT = 100_000_000_000_000.0
    const val MIN_SIMULATION_COUNT = 1000.0
    const val MAX_SIMULATION_COUNT = 10000.0
    const val MIN_HORIZON_YEARS = 1
    const val MAX_HORIZON_YEARS = 100
    const val MAX_WITHDRAWAL_TAX_RATE = 0.75
    const val MAX_TAX_BAND_RATE = 0.99
    const val MAX_ANNUAL_FEE_RATE = 0.1

    /** Upstream DEFAULT_SIMULATION_SEED. */
    val DEFAULT_SEED: UInt = 1234u

    // Meta resolution (upstream monteCarloConfigFromMeta)

    fun config(meta: MonteCarloMeta?): MonteCarloConfig {
        var config = MonteCarloConfig()
        meta?.pots?.takeIf { it.isNotEmpty() }?.let { potMetas ->
            config = config.copy(
                pots = potMetas.mapIndexed { index, potMeta ->
                    val pot = MonteCarloPot(potMeta.id?.takeIf { it.isNotEmpty() } ?: "pot-${index + 1}")
                    pot.copy(
                        name = potMeta.name ?: pot.name,
                        startingBalance = potMeta.startingBalance ?: pot.startingBalance,
                        allocationPreset = rawEnum<MonteCarloAllocationPreset>(potMeta.allocationPreset) { it.rawValue } ?: pot.allocationPreset,
                        expectedReturnMean = potMeta.expectedReturnMean ?: pot.expectedReturnMean,
                        returnStdDev = potMeta.returnStdDev ?: pot.returnStdDev,
                        accessAge = potMeta.accessAge,
                        accountId = potMeta.accountId,
                        withdrawalTaxRate = potMeta.withdrawalTaxRate ?: pot.withdrawalTaxRate,
                        taxableFraction = potMeta.taxableFraction ?: pot.taxableFraction,
                        annualFeeFixed = potMeta.annualFeeFixed ?: pot.annualFeeFixed,
                        feeAdjustsWithInflation = potMeta.feeAdjustsWithInflation ?: pot.feeAdjustsWithInflation,
                        annualFeeRate = potMeta.annualFeeRate ?: pot.annualFeeRate,
                    )
                },
            )
        }
        config = config.copy(
            withdrawalStrategy = rawEnum<MonteCarloWithdrawalStrategy>(meta?.withdrawalStrategy) { it.rawValue } ?: config.withdrawalStrategy,
            returnModel = rawEnum<MonteCarloReturnModel>(meta?.returnModel) { it.rawValue } ?: config.returnModel,
        )
        meta?.withdrawalRule?.let { ruleMeta ->
            val rule = MonteCarloWithdrawalRule()
            config = config.copy(
                withdrawalRule = MonteCarloWithdrawalRule(
                    type = rawEnum<MonteCarloWithdrawalRuleType>(ruleMeta.type) { it.rawValue } ?: rule.type,
                    prosperityTriggerPct = ruleMeta.prosperityTriggerPct ?: rule.prosperityTriggerPct,
                    prosperityIncreasePct = ruleMeta.prosperityIncreasePct ?: rule.prosperityIncreasePct,
                    preservationTriggerPct = ruleMeta.preservationTriggerPct ?: rule.preservationTriggerPct,
                    preservationCutPct = ruleMeta.preservationCutPct ?: rule.preservationCutPct,
                    balanceThresholdMultiple = ruleMeta.balanceThresholdMultiple ?: rule.balanceThresholdMultiple,
                    consecutiveYears = ruleMeta.consecutiveYears ?: rule.consecutiveYears,
                    ratchetIncreasePct = ruleMeta.ratchetIncreasePct ?: rule.ratchetIncreasePct,
                    floorPct = ruleMeta.floorPct ?: rule.floorPct,
                    ceilingPct = ruleMeta.ceilingPct ?: rule.ceilingPct,
                    upperRateThreshold = ruleMeta.upperRateThreshold ?: rule.upperRateThreshold,
                    upperCutPct = ruleMeta.upperCutPct ?: rule.upperCutPct,
                    lowerRateThreshold = ruleMeta.lowerRateThreshold ?: rule.lowerRateThreshold,
                    lowerIncreasePct = ruleMeta.lowerIncreasePct ?: rule.lowerIncreasePct,
                ),
            )
        }
        config = config.copy(minimumWithdrawal = meta?.minimumWithdrawal ?: config.minimumWithdrawal)
        meta?.spendingPhases?.takeIf { it.isNotEmpty() }?.let { phaseMetas ->
            config = config.copy(
                spendingPhases = phaseMetas.mapIndexed { index, phaseMeta ->
                    val phase = MonteCarloSpendingPhase(phaseMeta.id?.takeIf { it.isNotEmpty() } ?: "phase-${index + 1}")
                    phase.copy(name = phaseMeta.name ?: phase.name, fromAge = phaseMeta.fromAge, annualWithdrawal = phaseMeta.annualWithdrawal ?: phase.annualWithdrawal)
                },
            )
        }
        // Absent = default; explicit null = flat withdrawals
        meta?.inflationMean?.let { config = config.copy(inflationMean = it.numberOrNull) }
        config = config.copy(
            inflationStdDev = meta?.inflationStdDev ?: config.inflationStdDev,
            taxModel = rawEnum<MonteCarloTaxModel>(meta?.taxModel) { it.rawValue } ?: config.taxModel,
        )
        meta?.taxBands?.takeIf { it.isNotEmpty() }?.let { bandMetas ->
            config = config.copy(
                taxBands = bandMetas.mapIndexed { index, bandMeta ->
                    val band = MonteCarloTaxBand(bandMeta.id?.takeIf { it.isNotEmpty() } ?: "band-${index + 1}")
                    band.copy(from = bandMeta.from ?: band.from, rate = bandMeta.rate ?: band.rate)
                },
            )
        }
        return config.copy(
            currentAge = meta?.currentAge ?: config.currentAge,
            targetAge = meta?.targetAge ?: config.targetAge,
            simulationCount = meta?.simulationCount ?: config.simulationCount,
        )
    }

    /** Simulated years, derived from the configured ages (upstream getMonteCarloHorizonYears). */
    fun horizonYears(currentAge: Double, targetAge: Double): Int =
        // Clamp before converting so absurd ages can't overflow Int
        min(MAX_HORIZON_YEARS.toDouble(), max(MIN_HORIZON_YEARS.toDouble(), roundedHalfAwayFromZero(targetAge - currentAge))).toInt()

    /**
     * Card entry point: resolves account-linked pots then simulates in today's
     * money, as the dashboard card does upstream (MonteCarloCard.tsx).
     */
    fun compute(meta: MonteCarloMeta?, accountBalances: Map<String, Long> = emptyMap(), seed: UInt = DEFAULT_SEED): MonteCarloData {
        val resolved = config(meta)
        // Upstream useResolvedMonteCarloConfig: linked pots take the account's
        // live balance, falling back to the stored one
        val pots = resolved.pots.map { pot ->
            val balance = pot.accountId?.let(accountBalances::get) ?: return@map pot
            pot.copy(startingBalance = max(0L, balance).toDouble())
        }
        return simulate(resolved.copy(pots = pots), seed, deflateToTodaysMoney = true)
    }

    // Simulation (upstream runMonteCarloSimulation)

    /**
     * Each year, in each simulation: the withdrawal is taken at the start of
     * the year across the pots that have reached their access age; if the
     * accessible pots can't cover it the simulation is marked depleted for
     * that year; otherwise each pot earns that year's return. Returns are
     * nominal - inflation only grows withdrawals.
     */
    fun simulate(
        config: MonteCarloConfig,
        seed: UInt = DEFAULT_SEED,
        deflateToTodaysMoney: Boolean = false,
        historicalReturns: List<MonteCarloHistoricalReturn>? = null,
    ): MonteCarloData {
        fun clamp(value: Double, low: Double, high: Double) = min(high, max(low, value))

        val pots = config.pots.ifEmpty { listOf(MonteCarloPot("pot-1")) }
        val potCount = pots.size
        val potStartBalances = pots.map { clamp(it.startingBalance, 0.0, MAX_AMOUNT) }
        val potMeans = pots.map { it.expectedReturnMean }
        val potStdDevs = pots.map { max(0.0, it.returnStdDev) }
        val isSequential = config.withdrawalStrategy == MonteCarloWithdrawalStrategy.Sequential
        val isBestPerformer = config.withdrawalStrategy == MonteCarloWithdrawalStrategy.BestPerformer
        val isTargetMix = config.withdrawalStrategy == MonteCarloWithdrawalStrategy.TargetMix
        val previousReturns = DoubleArray(potCount)
        var drainOrder = (0 until potCount).toMutableList()
        val potIdealBalances = DoubleArray(potCount)
        val potTakes = DoubleArray(potCount)

        // First simulation year (1-based) in which each pot can fund
        // withdrawals; the year-y withdrawal happens at age currentAge + (y - 1)
        val potAccessFromYear = pots.map { pot ->
            val accessAge = pot.accessAge ?: return@map 1
            // Clamp before converting so absurd ages can't overflow Int; the
            // year is only ever compared against year <= maxHorizonYears
            val offset = clamp(roundedHalfAwayFromZero(accessAge - config.currentAge), 0.0, MAX_HORIZON_YEARS + 1.0)
            max(1, offset.toInt() + 1)
        }

        // Tax
        val taxModel = config.taxModel
        val potTaxRates = pots.map { clamp(it.withdrawalTaxRate, 0.0, MAX_WITHDRAWAL_TAX_RATE) }
        val potTaxableFractions = pots.map { clamp(it.taxableFraction, 0.0, 1.0) }
        // Bands sorted ascending (stable, like JS sort), with a guaranteed
        // 0-threshold first band so income below the first threshold is untaxed
        val taxBands = config.taxBands
            .map { clamp(it.from, 0.0, MAX_AMOUNT) to clamp(it.rate, 0.0, MAX_TAX_BAND_RATE) }
            .sortedBy { it.first }
            .toMutableList()
        if (taxBands.isEmpty() || taxBands[0].first > 0) taxBands.add(0, 0.0 to 0.0)
        val hasTax = if (taxModel == MonteCarloTaxModel.Bands) {
            taxBands.any { it.second > 0 } && potTaxableFractions.any { it > 0 }
        } else {
            potTaxRates.any { it > 0 }
        }

        // Fees
        val potFeeFixed = pots.map { clamp(it.annualFeeFixed, 0.0, MAX_AMOUNT) }
        val potFeeAdjusts = pots.map { it.feeAdjustsWithInflation }
        val potFeeRates = pots.map { clamp(it.annualFeeRate, 0.0, MAX_ANNUAL_FEE_RATE) }
        val hasFees = potFeeFixed.any { it > 0 } || potFeeRates.any { it > 0 }

        // Worst-case share of a gross withdrawal lost to tax; used to skip the
        // per-year shortfall precheck when capacity is comfortably sufficient
        val maxTaxRate = if (taxModel == MonteCarloTaxModel.Bands) taxBands.maxOf { it.second } else potTaxRates.fold(0.0, ::max)
        val minNetFactor = 1 - maxTaxRate

        /** Progressive tax on an annual taxable income, in today's money */
        fun bandTax(taxableIncome: Double): Double {
            var tax = 0.0
            for (bandIndex in taxBands.indices) {
                if (taxableIncome <= taxBands[bandIndex].first) break
                val upper = if (bandIndex + 1 < taxBands.size) taxBands[bandIndex + 1].first else Double.POSITIVE_INFINITY
                tax += taxBands[bandIndex].second * (min(taxableIncome, upper) - taxBands[bandIndex].first)
            }
            return tax
        }

        /**
         * Tax due on the takes currently in potTakes. Band thresholds are in
         * today's money, so taxable income is deflated by the replay's
         * inflation path before banding
         */
        fun taxForTakes(cumulativeInflationNow: Double): Double {
            if (!hasTax) return 0.0
            if (taxModel == MonteCarloTaxModel.Flat) {
                var tax = 0.0
                for (potIndex in 0 until potCount) tax += potTakes[potIndex] * potTaxRates[potIndex]
                return tax
            }
            var taxable = 0.0
            for (potIndex in 0 until potCount) taxable += potTakes[potIndex] * potTaxableFractions[potIndex]
            return bandTax(taxable / cumulativeInflationNow) * cumulativeInflationNow
        }

        val potBalances = DoubleArray(potCount)

        /** Split a gross withdrawal across pots per the configured strategy, writing each pot's take into potTakes (upstream computeTakes) */
        fun computeTakes(grossTotal: Double, year: Int, accessibleTotal: Double, lastAccessibleIndex: Int) {
            potTakes.fill(0.0)
            if (grossTotal <= 0 || accessibleTotal <= 0) return
            if (isSequential || isBestPerformer) {
                if (isBestPerformer) {
                    // Drain the pot with the highest return last year first;
                    // ties fall back to the listed order (JS stable sort)
                    drainOrder = (0 until potCount).sortedWith { potA, potB ->
                        if (previousReturns[potA] != previousReturns[potB]) previousReturns[potB].compareTo(previousReturns[potA]) else potA.compareTo(potB)
                    }.toMutableList()
                }
                var remaining = grossTotal
                for (orderIndex in 0 until potCount) {
                    if (remaining <= 0) break
                    val potIndex = if (isBestPerformer) drainOrder[orderIndex] else orderIndex
                    if (year < potAccessFromYear[potIndex]) continue
                    val take = min(potBalances[potIndex], remaining)
                    potTakes[potIndex] = take
                    remaining -= take
                }
            } else if (isTargetMix) {
                // Withdraw so the accessible pots move back toward their target
                // mix (weights = shares of the starting balances)
                var targetAccessible = 0.0
                for (potIndex in 0 until potCount) if (year >= potAccessFromYear[potIndex]) targetAccessible += potStartBalances[potIndex]
                val remainingTotal = accessibleTotal - grossTotal
                for (potIndex in 0 until potCount) {
                    potIdealBalances[potIndex] = if (year >= potAccessFromYear[potIndex] && targetAccessible > 0) {
                        (potStartBalances[potIndex] / targetAccessible) * remainingTotal
                    } else {
                        0.0
                    }
                }
                drainOrder = (0 until potCount).sortedWith { potA, potB ->
                    val excessA = potBalances[potA] - potIdealBalances[potA]
                    val excessB = potBalances[potB] - potIdealBalances[potB]
                    if (excessA != excessB) excessB.compareTo(excessA) else potA.compareTo(potB)
                }.toMutableList()
                var remaining = grossTotal
                for (orderIndex in 0 until potCount) {
                    if (remaining <= 0) break
                    val potIndex = drainOrder[orderIndex]
                    if (year < potAccessFromYear[potIndex]) continue
                    val excess = potBalances[potIndex] - potIdealBalances[potIndex]
                    if (excess <= 0) continue
                    val take = min(remaining, min(potBalances[potIndex], excess))
                    potTakes[potIndex] = take
                    remaining -= take
                }
                // Float-drift safety net: drain any accessible pot for whatever
                // tiny residue the excess passes left behind
                for (potIndex in 0 until potCount) {
                    if (remaining <= 0) break
                    if (year < potAccessFromYear[potIndex]) continue
                    val take = min(potBalances[potIndex] - potTakes[potIndex], remaining)
                    potTakes[potIndex] += take
                    remaining -= take
                }
            } else {
                // Proportional split across accessible pots; the last
                // accessible pot takes the remainder (no float drift)
                var remaining = grossTotal
                for (potIndex in 0 until max(0, lastAccessibleIndex)) {
                    if (year < potAccessFromYear[potIndex]) continue
                    val take = grossTotal * (potBalances[potIndex] / accessibleTotal)
                    potTakes[potIndex] = take
                    remaining -= take
                }
                if (lastAccessibleIndex >= 0) potTakes[lastAccessibleIndex] = remaining
            }
        }

        val returnModel = config.returnModel
        val history = historicalReturns?.takeIf { it.isNotEmpty() } ?: MonteCarloHistoricalReturn.all
        val historyCount = history.size

        // For historical models, each preset pot gets the blended return of its
        // asset mix in every historical year; 'custom' pots keep using normal
        // draws around their own mean/volatility
        val potHistoricalReturns: List<DoubleArray?> = pots.map { pot ->
            val weights = pot.allocationPreset.assetWeights
            if (returnModel == MonteCarloReturnModel.Normal || weights == null) {
                null
            } else {
                DoubleArray(historyCount) { history[it].stocks * weights.first + history[it].bonds * weights.second + history[it].cash * weights.third }
            }
        }
        val hasNormalDrawPot = potHistoricalReturns.any { it == null }

        val inflationMean = config.inflationMean
        val inflationStdDev = if (inflationMean != null) max(0.0, config.inflationStdDev) else 0.0
        val horizonYears = horizonYears(config.currentAge, config.targetAge)

        // The planned spending path in today's money: the active phase's amount
        // for every year (phases ordered by starting age, no-age first, stable
        // like upstream sortMonteCarloSpendingPhases)
        val spendingPhases = config.spendingPhases.ifEmpty { listOf(MonteCarloSpendingPhase("phase-1")) }
            .sortedBy { it.fromAge ?: Double.NEGATIVE_INFINITY }
        val plannedTodayByYear = DoubleArray(horizonYears + 1)
        for (year in 1..horizonYears) {
            val age = config.currentAge + (year - 1)
            var amount = clamp(spendingPhases[0].annualWithdrawal, 0.0, MAX_AMOUNT)
            for (phase in spendingPhases) {
                val fromAge = phase.fromAge
                if (fromAge == null || fromAge <= age) amount = clamp(phase.annualWithdrawal, 0.0, MAX_AMOUNT) else break
            }
            plannedTodayByYear[year] = amount
        }

        val deflate = deflateToTodaysMoney && inflationMean != null
        // Sequence replay runs exactly one scenario per historical start year
        val simulationCount = if (returnModel == MonteCarloReturnModel.HistoricalSequence) {
            historyCount
        } else {
            clamp(roundedHalfAwayFromZero(config.simulationCount), MIN_SIMULATION_COUNT, MAX_SIMULATION_COUNT).toInt()
        }

        val random = MonteCarloRandom(seed)

        // Year-major balance buffers (totals across pots)
        val balancesByYear = Array(horizonYears + 1) { DoubleArray(simulationCount) }
        val depletionCounts = IntArray(horizonYears + 1)
        var survivedCount = 0

        val startingTotal = potStartBalances.sum()
        val rule = config.withdrawalRule
        val minimumWithdrawal = clamp(config.minimumWithdrawal, 0.0, MAX_AMOUNT)

        // Keep every emitted amount formatter-safe; absurd configs flat-line at
        // the cap instead of overflowing (upstream toSafeAmount)
        val maxEmitted = (1L shl 50).toDouble() // 2^50, upstream maxEmitted
        fun toSafeAmount(value: Double) = clamp(value, -maxEmitted, maxEmitted)

        // Starting wealth accessible in each simulated year: withdrawal rules
        // measure rates against pots that can actually fund spending
        val accessibleStartByYear = DoubleArray(horizonYears + 1)
        for (year in 1..horizonYears) {
            var accessibleStart = 0.0
            for (potIndex in 0 until potCount) if (year >= potAccessFromYear[potIndex]) accessibleStart += potStartBalances[potIndex]
            accessibleStartByYear[year] = accessibleStart
        }

        val initialRate = if (accessibleStartByYear[1] > 0) plannedTodayByYear[1] / accessibleStartByYear[1] else 0.0

        for (simulationIndex in 0 until simulationCount) {
            for (potIndex in 0 until potCount) {
                potBalances[potIndex] = potStartBalances[potIndex]
                previousReturns[potIndex] = 0.0
            }
            var total = startingTotal
            // Cuts/raises from the withdrawal rule compound here, on top of the
            // planned spending path
            var adjustmentFactor = 1.0
            // This replay's realized inflation path
            var cumulativeInflation = 1.0
            var ratchetStreak = 0.0
            var depleted = false

            balancesByYear[0][simulationIndex] = toSafeAmount(total)

            for (year in 1..horizonYears) {
                // Post-depletion years stay at zero
                if (depleted) continue
                val planned = plannedTodayByYear[year] * cumulativeInflation
                var withdrawal: Double

                // Only pots that have reached their access age can fund this
                // year's withdrawal; locked pots stay invested but untouchable
                var accessibleTotal = 0.0
                var lastAccessibleIndex = -1
                for (potIndex in 0 until potCount) {
                    if (year >= potAccessFromYear[potIndex]) {
                        accessibleTotal += potBalances[potIndex]
                        lastAccessibleIndex = potIndex
                    }
                }

                // Apply the dynamic withdrawal rule before taking this year's
                // withdrawal (from year 2 - year 1 always uses the plan). Rules
                // evaluate accessible wealth only.
                if (year > 1 && rule.type == MonteCarloWithdrawalRuleType.FloorCeiling) {
                    // Recompute rule: a fixed share of the accessible balance,
                    // kept within limits around the planned spending
                    withdrawal = clamp(initialRate * accessibleTotal, planned * (1 - rule.floorPct), planned * (1 + rule.ceilingPct))
                } else {
                    if (year > 1 && rule.type != MonteCarloWithdrawalRuleType.None && accessibleTotal > 0 && accessibleStartByYear[year] > 0) {
                        val currentRate = (planned * adjustmentFactor) / accessibleTotal
                        when (rule.type) {
                            MonteCarloWithdrawalRuleType.Guardrails -> {
                                // Drift is measured against the planned spending
                                // path, so a deliberate phase change doesn't read
                                // as a trigger - only market-driven drift does
                                val referenceRate = plannedTodayByYear[year] / accessibleStartByYear[year]
                                if (currentRate > referenceRate * (1 + rule.preservationTriggerPct)) {
                                    adjustmentFactor *= 1 - rule.preservationCutPct
                                } else if (currentRate < referenceRate * (1 - rule.prosperityTriggerPct)) {
                                    adjustmentFactor *= 1 + rule.prosperityIncreasePct
                                }
                            }
                            MonteCarloWithdrawalRuleType.Ratcheting -> {
                                if (accessibleTotal > accessibleStartByYear[year] * rule.balanceThresholdMultiple) {
                                    ratchetStreak += 1
                                    if (ratchetStreak >= rule.consecutiveYears) {
                                        adjustmentFactor *= 1 + rule.ratchetIncreasePct
                                        ratchetStreak = 0.0
                                    }
                                } else {
                                    ratchetStreak = 0.0
                                }
                            }
                            MonteCarloWithdrawalRuleType.Boundaries -> {
                                if (currentRate > rule.upperRateThreshold) {
                                    adjustmentFactor *= 1 - rule.upperCutPct
                                } else if (currentRate < rule.lowerRateThreshold) {
                                    adjustmentFactor *= 1 + rule.lowerIncreasePct
                                }
                            }
                            MonteCarloWithdrawalRuleType.None, MonteCarloWithdrawalRuleType.FloorCeiling -> Unit
                        }
                    }
                    withdrawal = planned * adjustmentFactor
                }
                // The minimum floor guards against rule-driven cuts, so it only
                // applies with an active rule in years with planned spending;
                // like the phase amounts it's in today's money
                val minimumThisYear = minimumWithdrawal * cumulativeInflation
                if (rule.type != MonteCarloWithdrawalRuleType.None && minimumWithdrawal > 0 && planned > 0 && withdrawal < minimumThisYear) {
                    withdrawal = minimumThisYear
                }

                // The spending requirement is net of tax; the gross that leaves
                // the pots must deliver it
                val netRequired = withdrawal
                var fundingShortfall = false

                // Net capacity: what withdrawing every accessible penny would
                // actually deliver after tax
                var accessibleNetCapacity = accessibleTotal
                if (hasTax && accessibleTotal * minNetFactor <= netRequired) {
                    computeTakes(accessibleTotal, year, accessibleTotal, lastAccessibleIndex)
                    accessibleNetCapacity = accessibleTotal - taxForTakes(cumulativeInflation)
                }

                if (accessibleNetCapacity <= netRequired) {
                    fundingShortfall = true
                    // The accessible pots can't cover this year's spending
                    // (locked pots may still hold money)
                    potBalances.fill(0.0)
                    total = 0.0
                    depleted = true
                    depletionCounts[year] += 1
                } else {
                    // Solve the gross withdrawal that delivers the net
                    // requirement: g = net + tax(takes(g)). Tax is piecewise
                    // linear in g, so this fixed point converges geometrically
                    var grossTotal = netRequired
                    if (hasTax) {
                        for (iteration in 0 until 40) {
                            computeTakes(grossTotal, year, accessibleTotal, lastAccessibleIndex)
                            val next = netRequired + taxForTakes(cumulativeInflation)
                            if (abs(next - grossTotal) <= 1e-7 * max(1.0, next)) {
                                grossTotal = next
                                break
                            }
                            grossTotal = next
                        }
                        grossTotal = min(grossTotal, accessibleTotal)
                    }

                    computeTakes(grossTotal, year, accessibleTotal, lastAccessibleIndex)
                    for (potIndex in 0 until potCount) potBalances[potIndex] -= potTakes[potIndex]

                    // Every pot experiences the same market year: historical
                    // models pick one history year for all pots, and
                    // normally-drawn pots share one market shock scaled by
                    // their own volatility
                    val historyIndex = when (returnModel) {
                        MonteCarloReturnModel.HistoricalBootstrap -> (random.next() * historyCount).toInt()
                        MonteCarloReturnModel.HistoricalSequence -> (simulationIndex + year - 1) % historyCount
                        MonteCarloReturnModel.Normal -> -1
                    }
                    val marketShock = if (hasNormalDrawPot) random.nextNormal() else 0.0

                    total = 0.0
                    for (potIndex in 0 until potCount) {
                        if (potBalances[potIndex] > 0) {
                            val blended = potHistoricalReturns[potIndex]
                            val yearReturn = if (blended != null && historyIndex >= 0) {
                                blended[historyIndex]
                            } else {
                                potMeans[potIndex] + potStdDevs[potIndex] * marketShock
                            }
                            previousReturns[potIndex] = yearReturn
                            potBalances[potIndex] *= 1 + yearReturn
                            // A sub-(-100%) return draw wiped this pot out
                            if (potBalances[potIndex] <= 0) potBalances[potIndex] = 0.0
                        }
                        total += potBalances[potIndex]
                    }
                    if (total <= 0) {
                        total = 0.0
                        depleted = true
                        depletionCounts[year] += 1
                    }
                }

                // Realize this year's inflation, drawing a random rate when
                // volatility is set (fixed mean otherwise)
                if (inflationMean != null) {
                    val yearInflation = if (inflationStdDev > 0) max(-0.9, inflationMean + inflationStdDev * random.nextNormal()) else inflationMean
                    cumulativeInflation *= 1 + yearInflation
                }

                // Management fees come out at the end of the year, after growth
                // and inflation; fees can deplete a plan on their own
                if (hasFees && !fundingShortfall && total > 0) {
                    for (potIndex in 0 until potCount) {
                        if (potBalances[potIndex] <= 0) continue
                        val fee = potBalances[potIndex] * potFeeRates[potIndex] +
                            potFeeFixed[potIndex] * (if (potFeeAdjusts[potIndex]) cumulativeInflation else 1.0)
                        potBalances[potIndex] -= min(potBalances[potIndex], fee)
                    }
                    total = potBalances.sum()
                    if (total <= 0) {
                        total = 0.0
                        depleted = true
                        depletionCounts[year] += 1
                    }
                }
                val endDeflator = if (deflate) 1 / cumulativeInflation else 1.0

                // Store this replay's balance, in today's money when deflating
                balancesByYear[year][simulationIndex] = toSafeAmount(total * endDeflator)
            }

            if (!depleted) survivedCount += 1
        }

        fun percentileOfSorted(sorted: DoubleArray, percentile: Double): Double {
            val position = (sorted.size - 1) * percentile
            val lower = floor(position).toInt()
            val upper = ceil(position).toInt()
            if (lower == upper) return sorted[lower]
            val weight = position - lower
            return sorted[lower] * (1 - weight) + sorted[upper] * weight
        }

        val percentileBands = (0..horizonYears).map { year ->
            val sorted = balancesByYear[year].sortedArray()
            fun p(percentile: Double) = roundedHalfAwayFromZero(percentileOfSorted(sorted, percentile)).toLong()
            MonteCarloPercentileBand(year, p(0.05), p(0.1), p(0.25), p(0.3), p(0.5), p(0.7), p(0.75), p(0.9))
        }

        return MonteCarloData(survivedCount.toDouble() / simulationCount, config.currentAge, horizonYears, simulationCount, percentileBands)
    }
}
