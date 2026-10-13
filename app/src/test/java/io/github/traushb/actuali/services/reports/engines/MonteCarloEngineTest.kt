package io.github.traushb.actuali.services.reports.engines

import io.github.traushb.actuali.services.AppJson
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Mirrors upstream monteCarloSimulation.test.ts. Deterministic (zero
 * volatility) scenarios are hand-computable; seeded scenarios pin values
 * computed with the upstream mulberry32/Box-Muller PRNG in node.
 */
class MonteCarloEngineTest {
    /** Upstream makePot: custom preset, mean 0.06, stdDev 0.1, balance 50M cents. */
    private fun pot(
        id: String = "pot-1",
        balance: Double = 50_000_000.0,
        mean: Double = 0.06,
        stdDev: Double = 0.1,
        accessAge: Double? = null,
        taxRate: Double = 0.0,
        taxableFraction: Double = 1.0,
        feeFixed: Double = 0.0,
        feeRate: Double = 0.0,
        preset: MonteCarloAllocationPreset = MonteCarloAllocationPreset.Custom,
    ) = MonteCarloPot(
        id = id, startingBalance = balance, allocationPreset = preset, expectedReturnMean = mean, returnStdDev = stdDev,
        accessAge = accessAge, withdrawalTaxRate = taxRate, taxableFraction = taxableFraction, annualFeeFixed = feeFixed, annualFeeRate = feeRate,
    )

    /** Upstream makeParams: single phase, no inflation, no tax bands, horizon via targetAge = 60 + horizonYears, 1000 sims. */
    private fun config(
        pots: List<MonteCarloPot>,
        annualWithdrawal: Double = 2_000_000.0,
        strategy: MonteCarloWithdrawalStrategy = MonteCarloWithdrawalStrategy.Proportional,
        returnModel: MonteCarloReturnModel = MonteCarloReturnModel.Normal,
        rule: MonteCarloWithdrawalRule = MonteCarloWithdrawalRule(),
        minimumWithdrawal: Double = 0.0,
        phases: List<MonteCarloSpendingPhase>? = null,
        inflationMean: Double? = null,
        taxModel: MonteCarloTaxModel = MonteCarloTaxModel.Flat,
        taxBands: List<MonteCarloTaxBand> = emptyList(),
        horizonYears: Double = 30.0,
    ) = MonteCarloConfig(
        pots = pots,
        withdrawalStrategy = strategy,
        returnModel = returnModel,
        withdrawalRule = rule,
        minimumWithdrawal = minimumWithdrawal,
        spendingPhases = phases ?: listOf(MonteCarloSpendingPhase("phase-1", annualWithdrawal = annualWithdrawal)),
        inflationMean = inflationMean,
        inflationStdDev = 0.0,
        taxModel = taxModel,
        taxBands = taxBands,
        currentAge = 60.0,
        targetAge = 60 + horizonYears,
        simulationCount = 1000.0,
    )

    private fun simulate(config: MonteCarloConfig, seed: UInt = 42u, history: List<MonteCarloHistoricalReturn>? = null) =
        MonteCarloEngine.simulate(config, seed, historicalReturns = history)

    /** In deterministic runs every simulation is identical, so the first year whose median is zero is the depletion year. */
    private fun firstDepletedYear(data: MonteCarloData): Int? = data.percentileBands.firstOrNull { it.year >= 1 && it.p50 == 0L }?.year

    // PRNG parity with upstream

    @Test
    fun mulberry32MatchesUpstreamSequence() {
        // First five uniforms of mulberry32(42) computed with the upstream JS implementation in node
        val random = MonteCarloRandom(42u)
        for (value in listOf(0.60110375192016363, 0.44829055899754167, 0.85246579349040985, 0.66973404143936932, 0.17481389874592423)) {
            assertEquals(value, random.next(), 0.0)
        }
    }

    @Test
    fun boxMullerMatchesUpstreamSequence() {
        // First four normals of makeNormalSampler(mulberry32(42)) in node
        val random = MonteCarloRandom(42u)
        for (value in listOf(-1.2848381576290195, -0.94535280997472959, -0.61128028465146289, -0.56583258521268753)) {
            assertEquals(value, random.nextNormal(), 0.0)
        }
    }

    @Test
    fun seededRunMatchesUpstreamReplica() {
        // Pinned from a node replica of the upstream engine's exact random-call
        // order for this config (seed 42, 1000 sims x 30 years, single custom
        // pot 50M, withdrawal 2M, mean 0.06, sd 0.1)
        val result = simulate(config(listOf(pot())))
        assertEquals(0.971, result.successRate, 0.0)
        assertEquals(51_097_236L, result.percentileBands[1].p50)
        assertEquals(44_738_562L, result.percentileBands[1].p10)
        assertEquals(57_505_924L, result.percentileBands[1].p90)
        assertEquals(95_582_181L, result.percentileBands[30].p50)
    }

    @Test
    fun horizonDerivedFromAgesAndClamped() {
        assertEquals(30, MonteCarloEngine.horizonYears(60.0, 90.0))
        assertEquals(28, MonteCarloEngine.horizonYears(62.4, 90.0))
        assertEquals(1, MonteCarloEngine.horizonYears(90.0, 60.0))
        assertEquals(1, MonteCarloEngine.horizonYears(70.0, 70.0))
        assertEquals(100, MonteCarloEngine.horizonYears(0.0, 500.0))
    }

    // Deterministic closed-form scenarios

    @Test
    fun closedFormDepletionWithZeroVolatility() {
        // Pot 1.2M cents, flat 0% return, withdraw 100k/yr: 12 full years, hits exactly zero (and fails) in year 12
        val result = simulate(config(listOf(pot(balance = 1_200_000.0, mean = 0.0, stdDev = 0.0)), annualWithdrawal = 100_000.0))
        assertEquals(0.0, result.successRate, 0.0)
        assertEquals(12, firstDepletedYear(result))
        assertEquals(100_000L, result.percentileBands[11].p50)
        assertEquals(1_200_000L, result.percentileBands[0].p50)
    }

    @Test
    fun survivesWhenReturnsOutpaceWithdrawals() {
        val result = simulate(config(listOf(pot(balance = 100_000.0, mean = 0.05, stdDev = 0.0)), annualWithdrawal = 3000.0))
        assertEquals(1.0, result.successRate, 0.0)
        assertNull(firstDepletedYear(result))
        assertTrue(result.percentileBands[30].p50 > 100_000)
    }

    @Test
    fun alwaysSucceedsWithNoWithdrawals() = assertEquals(1.0, simulate(config(listOf(pot(stdDev = 0.2)), annualWithdrawal = 0.0)).successRate, 0.0)

    @Test
    fun depletesInYearOneWhenWithdrawalExceedsPot() {
        val result = simulate(config(listOf(pot(balance = 10000.0)), annualWithdrawal = 20000.0))
        assertEquals(0.0, result.successRate, 0.0)
        assertEquals(1, firstDepletedYear(result))
    }

    @Test
    fun inflationGrowsWithdrawals() {
        // pot 200, withdrawal 50, no growth, 100% inflation: y1: 150 (w -> 100); y2: 50 (w -> 200); y3: depleted
        val inflated = simulate(config(listOf(pot(balance = 200.0, mean = 0.0, stdDev = 0.0)), annualWithdrawal = 50.0, inflationMean = 1.0, horizonYears = 10.0))
        assertEquals(3, firstDepletedYear(inflated))
        // Flat withdrawals last one year longer: y4 hits exactly 0
        val flat = simulate(config(listOf(pot(balance = 200.0, mean = 0.0, stdDev = 0.0)), annualWithdrawal = 50.0, inflationMean = null, horizonYears = 10.0))
        assertEquals(4, firstDepletedYear(flat))
    }

    @Test
    fun spendingPhasesSwitchAtStartingAge() {
        // Ages 60-64 spend 10k (years 1-5), from 65 spend 20k
        val result = simulate(
            config(
                listOf(pot(balance = 1_000_000.0, mean = 0.0, stdDev = 0.0)),
                phases = listOf(MonteCarloSpendingPhase("phase-1", annualWithdrawal = 10000.0), MonteCarloSpendingPhase("phase-2", fromAge = 65.0, annualWithdrawal = 20000.0)),
                horizonYears = 13.0,
            ),
        )
        assertEquals(950_000L, result.percentileBands[5].p50)
        assertEquals(930_000L, result.percentileBands[6].p50)
        assertEquals(1_000_000L - 5 * 10000 - 8 * 20000, result.percentileBands[13].p50)
    }

    // Withdrawal rules

    @Test
    fun boundariesCutWithdrawalsAndExtendSurvival() {
        // 10% withdrawal rate on a flat pot depletes in exactly year 10
        val base = config(listOf(pot(balance = 100_000.0, mean = 0.0, stdDev = 0.0)), annualWithdrawal = 10000.0)
        assertEquals(10, firstDepletedYear(simulate(base)))
        val withRule = simulate(base.copy(withdrawalRule = MonteCarloWithdrawalRule(type = MonteCarloWithdrawalRuleType.Boundaries)))
        assertTrue((firstDepletedYear(withRule) ?: Int.MAX_VALUE) > 10)
    }

    @Test
    fun floorCeilingScalesWithdrawalsWithinLimits() {
        val rule = MonteCarloWithdrawalRule(type = MonteCarloWithdrawalRuleType.FloorCeiling, floorPct = 0.5, ceilingPct = 0.5)
        val result = simulate(config(listOf(pot(balance = 100_000.0, mean = 0.0, stdDev = 0.0)), annualWithdrawal = 10000.0, rule = rule))
        assertTrue((firstDepletedYear(result) ?: Int.MAX_VALUE) > 14)
    }

    @Test
    fun guardrailsRaiseWithdrawalsWhenPotRacesAhead() {
        // 4% initial rate with steady 20% growth triggers prosperity raises;
        // the extra income leaves a lower ending balance than no rule
        val base = config(listOf(pot(balance = 100_000.0, mean = 0.2, stdDev = 0.0)), annualWithdrawal = 4000.0, horizonYears = 20.0)
        val withoutRule = simulate(base)
        val withRule = simulate(base.copy(withdrawalRule = MonteCarloWithdrawalRule(type = MonteCarloWithdrawalRuleType.Guardrails)))
        assertEquals(1.0, withRule.successRate, 0.0)
        assertTrue(withRule.percentileBands[20].p50 < withoutRule.percentileBands[20].p50)
    }

    @Test
    fun ratchetingIncreasesWithdrawalsAfterConsecutiveYears() {
        val base = config(listOf(pot(balance = 100_000.0, mean = 0.3, stdDev = 0.0)), annualWithdrawal = 1000.0, horizonYears = 20.0)
        val withoutRule = simulate(base)
        val rule = MonteCarloWithdrawalRule(type = MonteCarloWithdrawalRuleType.Ratcheting, balanceThresholdMultiple = 1.5, consecutiveYears = 3.0, ratchetIncreasePct = 0.5)
        val withRule = simulate(base.copy(withdrawalRule = rule))
        assertTrue(withRule.percentileBands[20].p50 < withoutRule.percentileBands[20].p50)
    }

    @Test
    fun minimumWithdrawalFloorNeutralizesRuleCuts() {
        val base = config(listOf(pot(balance = 100_000.0, mean = 0.0, stdDev = 0.0)), annualWithdrawal = 10000.0)
        val floored = simulate(base.copy(withdrawalRule = MonteCarloWithdrawalRule(type = MonteCarloWithdrawalRuleType.Boundaries), minimumWithdrawal = 10000.0))
        assertEquals(simulate(base).percentileBands, floored.percentileBands)
    }

    /** Upstream: with no rule the planned spending is taken as-is. */
    @Test
    fun minimumWithdrawalIgnoredWithoutActiveRule() {
        val base = config(listOf(pot(balance = 100_000.0, mean = 0.0, stdDev = 0.0)), annualWithdrawal = 5000.0)
        assertEquals(simulate(base).percentileBands, simulate(base.copy(minimumWithdrawal = 50000.0)).percentileBands)
    }

    // Access ages

    @Test
    fun failsWhenAccessiblePotsRunDryBeforeLockedPotUnlocks() {
        // Accessible 100 funds 30/year: year 4 fails; the pot locked until age 120 doesn't save the plan
        val result = simulate(
            config(
                listOf(pot(id = "isa", balance = 100.0, mean = 0.0, stdDev = 0.0), pot(id = "pension", balance = 1000.0, mean = 0.0, stdDev = 0.0, accessAge = 120.0)),
                annualWithdrawal = 30.0, horizonYears = 20.0,
            ),
        )
        assertEquals(0.0, result.successRate, 0.0)
        assertEquals(4, firstDepletedYear(result))
    }

    @Test
    fun survivesWhenLockedPotUnlocksInTime() {
        // Same setup, but the pension unlocks at 63 - exactly year 4
        val result = simulate(
            config(
                listOf(pot(id = "isa", balance = 100.0, mean = 0.0, stdDev = 0.0), pot(id = "pension", balance = 1000.0, mean = 0.0, stdDev = 0.0, accessAge = 63.0)),
                annualWithdrawal = 30.0, horizonYears = 20.0,
            ),
        )
        assertEquals(1.0, result.successRate, 0.0)
    }

    @Test
    fun accessAgeAtOrBelowCurrentAgeIsImmediate() =
        assertEquals(simulate(config(listOf(pot(accessAge = null)))), simulate(config(listOf(pot(accessAge = 40.0)))))

    @Test
    fun sequentialOrderSkipsLockedPotsUntilTheyUnlock() {
        // First-listed pot locked past the horizon, so sequential drains the second: 100 at 10/year fails in year 10
        val result = simulate(
            config(
                listOf(pot(id = "locked", balance = 1000.0, mean = 0.0, stdDev = 0.0, accessAge = 200.0), pot(id = "open", balance = 100.0, mean = 0.0, stdDev = 0.0)),
                annualWithdrawal = 10.0, strategy = MonteCarloWithdrawalStrategy.Sequential, horizonYears = 20.0,
            ),
        )
        assertEquals(0.0, result.successRate, 0.0)
        assertEquals(10, firstDepletedYear(result))
    }

    // Tax

    @Test
    fun flatTaxGrossesUpWithdrawals() {
        // 20% tax pot: delivering 8,000 net costs 10,000 gross each year, so 95,000 funds nine full years and fails in year ten
        val result = simulate(config(listOf(pot(balance = 95000.0, mean = 0.0, stdDev = 0.0, taxRate = 0.2)), annualWithdrawal = 8000.0, horizonYears = 12.0))
        assertEquals(0.0, result.successRate, 0.0)
        assertEquals(10, firstDepletedYear(result))
        assertEquals(5000L, result.percentileBands[9].p50)
    }

    @Test
    fun taxBandsGrossUpProgressively() {
        // Bands: first 10,000 tax-free, 20% above. Delivering 18,000 net needs 20,000 gross each year
        val result = simulate(
            config(
                listOf(pot(balance = 200_000.0, mean = 0.0, stdDev = 0.0)), annualWithdrawal = 18000.0, taxModel = MonteCarloTaxModel.Bands,
                taxBands = listOf(MonteCarloTaxBand("a", 0.0, 0.0), MonteCarloTaxBand("b", 10000.0, 0.2)), horizonYears = 3.0,
            ),
        )
        assertEquals(180_000L, result.percentileBands[1].p50)
        assertEquals(160_000L, result.percentileBands[2].p50)
        assertEquals(140_000L, result.percentileBands[3].p50)
    }

    @Test
    fun taxBandsPoolTaxableIncomeAcrossPots() {
        // Proportional split takes half the gross from each pot; only the
        // pension half is 75% taxable, so taxable income is 0.375x gross.
        // Solving 0.925g = 38,000 gives g = 41,081.08 for 40,000 net
        val result = simulate(
            config(
                listOf(pot(id = "isa", balance = 100_000.0, mean = 0.0, stdDev = 0.0, taxableFraction = 0.0), pot(id = "pension", balance = 100_000.0, mean = 0.0, stdDev = 0.0, taxableFraction = 0.75)),
                annualWithdrawal = 40000.0, taxModel = MonteCarloTaxModel.Bands,
                taxBands = listOf(MonteCarloTaxBand("a", 0.0, 0.0), MonteCarloTaxBand("b", 10000.0, 0.2)), horizonYears = 1.0,
            ),
        )
        // 200,000 - 41,081.081... = 158,918.92 rounds to 158,919
        assertEquals(158_919L, result.percentileBands[1].p50)
    }

    @Test
    fun singleTaxBandBehavesLikeFlatRate() {
        val flat = simulate(config(listOf(pot(taxRate = 0.25))))
        val bands = simulate(config(listOf(pot(taxableFraction = 1.0)), taxModel = MonteCarloTaxModel.Bands, taxBands = listOf(MonteCarloTaxBand("a", 0.0, 0.25))))
        assertEquals(flat.successRate, bands.successRate, 0.0)
        assertTrue(abs(bands.percentileBands[30].p50 - flat.percentileBands[30].p50) <= 1)
    }

    @Test
    fun zeroRateTaxMatchesUntaxedEngineExactly() {
        val untaxed = simulate(config(listOf(pot())))
        val zeroBands = simulate(config(listOf(pot()), taxModel = MonteCarloTaxModel.Bands, taxBands = listOf(MonteCarloTaxBand("a", 0.0, 0.0))))
        assertEquals(untaxed.percentileBands, zeroBands.percentileBands)
        assertEquals(untaxed.successRate, zeroBands.successRate, 0.0)
    }

    // Fees

    @Test
    fun fixedFeeDeductedAtYearEnd() {
        val result = simulate(config(listOf(pot(balance = 100_000.0, mean = 0.0, stdDev = 0.0, feeFixed = 1000.0)), annualWithdrawal = 0.0, horizonYears = 5.0))
        assertEquals(99000L, result.percentileBands[1].p50)
        assertEquals(98000L, result.percentileBands[2].p50)
    }

    @Test
    fun percentageFeeDeductedFromEndOfYearBalance() {
        val result = simulate(config(listOf(pot(balance = 100_000.0, mean = 0.0, stdDev = 0.0, feeRate = 0.01)), annualWithdrawal = 0.0, horizonYears = 5.0))
        assertEquals(99000L, result.percentileBands[1].p50)
        assertEquals(98010L, result.percentileBands[2].p50)
    }

    @Test
    fun feesAloneCanDepleteAPlan() {
        val result = simulate(config(listOf(pot(balance = 10000.0, mean = 0.0, stdDev = 0.0, feeFixed = 5000.0)), annualWithdrawal = 0.0, horizonYears = 10.0))
        assertEquals(0.0, result.successRate, 0.0)
        assertEquals(2, firstDepletedYear(result))
    }

    // Historical models

    @Test
    fun bootstrapWithOneYearHistoryIsDeterministic() {
        // A single history year means every bootstrap draw returns it; an equity-60 pot earns 10% every year
        val result = simulate(
            config(listOf(pot(balance = 100_000.0, mean = 0.0, stdDev = 0.0, preset = MonteCarloAllocationPreset.Equity60)), annualWithdrawal = 10000.0, returnModel = MonteCarloReturnModel.HistoricalBootstrap, horizonYears = 5.0),
            history = listOf(MonteCarloHistoricalReturn(2000, 0.1, 0.1, 0.1)),
        )
        // (100,000 - 10,000) * 1.1 = 99,000; (99,000 - 10,000) * 1.1 = 97,900
        assertEquals(99000L, result.percentileBands[1].p50)
        assertEquals(97900L, result.percentileBands[2].p50)
    }

    @Test
    fun sequenceModeRunsOneScenarioPerStartYear() {
        val history = listOf(MonteCarloHistoricalReturn(2000, 0.1, 0.1, 0.1), MonteCarloHistoricalReturn(2001, -0.2, -0.2, -0.2), MonteCarloHistoricalReturn(2002, 0.05, 0.05, 0.05))
        val result = simulate(config(listOf(pot(preset = MonteCarloAllocationPreset.Equity60)), returnModel = MonteCarloReturnModel.HistoricalSequence, horizonYears = 10.0), history = history)
        assertEquals(history.size, result.simulationCount)
    }

    // Determinism and statistics

    @Test
    fun deterministicForAGivenSeed() {
        val firstRun = simulate(config(listOf(pot())), seed = 7u)
        assertEquals(firstRun, simulate(config(listOf(pot())), seed = 7u))
        assertNotEquals(firstRun.percentileBands, simulate(config(listOf(pot())), seed = 8u).percentileBands)
    }

    @Test
    fun producesOrderedPercentileBands() {
        val result = simulate(config(listOf(pot(stdDev = 0.15))))
        for (band in result.percentileBands) {
            assertTrue(band.p5 <= band.p10 && band.p10 <= band.p25 && band.p25 <= band.p30 && band.p30 <= band.p50)
            assertTrue(band.p50 <= band.p70 && band.p70 <= band.p75 && band.p75 <= band.p90)
        }
        assertEquals(result.horizonYears + 1, result.percentileBands.size)
        assertEquals(50_000_000L, result.percentileBands[0].p10)
        assertEquals(50_000_000L, result.percentileBands[0].p90)
    }

    // Meta decoding and account resolution

    @Test
    fun metaDistinguishesNullInflationFromAbsent() {
        val explicitNull = AppJson.decodeFromString(MonteCarloMeta.serializer(), """{"inflationMean": null}""")
        assertNull(MonteCarloEngine.config(explicitNull).inflationMean)
        val absent = AppJson.decodeFromString(MonteCarloMeta.serializer(), "{}")
        assertEquals(0.025, MonteCarloEngine.config(absent).inflationMean!!, 0.0)
    }

    @Test
    fun configFromMetaAppliesUpstreamDefaults() {
        val meta = AppJson.decodeFromString(
            MonteCarloMeta.serializer(),
            """{"pots":[{"id":"p1","startingBalance":123}],"withdrawalStrategy":"best-performer","returnModel":"historical-bootstrap","currentAge":55,"targetAge":85}""",
        )
        val config = MonteCarloEngine.config(meta)
        assertEquals(1, config.pots.size)
        assertEquals(123.0, config.pots[0].startingBalance, 0.0)
        assertEquals(MonteCarloAllocationPreset.Equity60, config.pots[0].allocationPreset)
        assertEquals(1.0, config.pots[0].taxableFraction, 0.0)
        assertEquals(MonteCarloWithdrawalStrategy.BestPerformer, config.withdrawalStrategy)
        assertEquals(MonteCarloReturnModel.HistoricalBootstrap, config.returnModel)
        assertEquals(MonteCarloWithdrawalRuleType.None, config.withdrawalRule.type)
        assertEquals(55.0, config.currentAge, 0.0)
        assertEquals(85.0, config.targetAge, 0.0)
        assertEquals(5000.0, config.simulationCount, 0.0)
    }

    @Test
    fun computeResolvesAccountLinkedPotBalances() {
        // A pot linked to an account takes the account's live balance (clamped
        // at zero); unlinked pots keep their stored balance
        val meta = MonteCarloMeta(
            pots = listOf(MonteCarloPotMeta(id = "p1", startingBalance = 1_200_000.0, allocationPreset = "custom", expectedReturnMean = 0.0, returnStdDev = 0.0, accountId = "acct-1")),
            spendingPhases = listOf(MonteCarloSpendingPhaseMeta(id = "s1", annualWithdrawal = 100_000.0)),
            inflationMean = JsonNull,
            currentAge = 60.0,
            targetAge = 90.0,
        )
        // 600k cents from the account instead of the stored 1.2M: depletes in year 6 instead of year 12
        val result = MonteCarloEngine.compute(meta, mapOf("acct-1" to 600_000L))
        assertEquals(0.0, result.successRate, 0.0)
        assertEquals(6, firstDepletedYear(result))
        assertEquals(0L, MonteCarloEngine.compute(meta, mapOf("acct-1" to -500L)).percentileBands[0].p50)
        assertEquals(12, firstDepletedYear(MonteCarloEngine.compute(meta)))
    }
}
