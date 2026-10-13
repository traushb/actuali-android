package io.github.traushb.actuali.services.reports

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TimeFrameTest {
    /** 2026-05-14 fixed reference date for deterministic tests. */
    private val referenceDate = LocalDate.of(2026, 5, 14)

    private fun resolve(tf: WidgetTimeFrame?) = TimeFrame.resolve(tf, referenceDate).let { it.first.toString() to it.second.toString() }

    /** Upstream Actual defaults summary widgets to the current month when no timeFrame is set. */
    @Test
    fun nilTimeFrameDefaultsToCurrentMonth() = assertEquals("2026-05-01" to "2026-05-31", resolve(null))

    @Test
    fun lastMonth() = assertEquals("2026-04-01" to "2026-04-30", resolve(WidgetTimeFrame(mode = WidgetTimeFrame.Mode.LastMonth)))

    @Test
    fun lastYear() = assertEquals("2025-01-01" to "2025-12-31", resolve(WidgetTimeFrame(mode = WidgetTimeFrame.Mode.LastYear)))

    @Test
    fun yearToDate() = assertEquals("2026-01-01" to "2026-05-14", resolve(WidgetTimeFrame(mode = WidgetTimeFrame.Mode.YearToDate)))

    @Test
    fun priorYearToDate() = assertEquals("2025-01-01" to "2025-05-14", resolve(WidgetTimeFrame(mode = WidgetTimeFrame.Mode.PriorYearToDate)))

    @Test
    fun staticUsesProvidedFullDates() =
        assertEquals("2024-03-01" to "2024-04-15", resolve(WidgetTimeFrame("2024-03-01", "2024-04-15", WidgetTimeFrame.Mode.Static)))

    /** The webapp stores most timeFrames as YYYY-MM; start snaps to month start, end expands to month end. */
    @Test
    fun staticAcceptsYearMonthFormat() = assertEquals("2025-08-01" to "2026-04-30", resolve(WidgetTimeFrame("2025-08", "2026-04", WidgetTimeFrame.Mode.Static)))

    @Test
    fun staticRejectsNonCanonicalDateParts() = assertEquals("2026-05-01" to "2026-05-14", resolve(WidgetTimeFrame("2025-8", "2026-4-15", WidgetTimeFrame.Mode.Static)))

    @Test
    fun fullWithNilBoundsReturnsWideRange() = assertEquals("1900-01-01" to "2026-05-31", resolve(WidgetTimeFrame(mode = WidgetTimeFrame.Mode.Full)))

    @Test
    fun fullHonorsStoredStart() = assertEquals("2025-04-01", resolve(WidgetTimeFrame("2025-04", null, WidgetTimeFrame.Mode.Full)).first)

    /** mode=full uses current-month end regardless of any stored end (upstream getFullRange). */
    @Test
    fun fullIgnoresStoredEnd() = assertEquals("2025-04-01" to "2026-05-31", resolve(WidgetTimeFrame("2025-04", "2026-04", WidgetTimeFrame.Mode.Full)))

    @Test
    fun slidingWindowWithoutBoundsDefaultsToCurrentMonth() =
        assertEquals("2026-05-01" to "2026-05-31", resolve(WidgetTimeFrame(mode = WidgetTimeFrame.Mode.SlidingWindow)))

    /** A 1-month window stored as April 2026 slides to May 2026 when today is May 14 2026. */
    @Test
    fun slidingWindowOneMonthSlidesForward() =
        assertEquals("2026-05-01" to "2026-05-31", resolve(WidgetTimeFrame("2026-04", "2026-04", WidgetTimeFrame.Mode.SlidingWindow)))

    /** 3-month window stored Feb → Apr 2026, today May 14 2026: shift by one month to Mar → May 31. */
    @Test
    fun slidingWindowThreeMonthsSlidesForward() =
        assertEquals("2026-03-01" to "2026-05-31", resolve(WidgetTimeFrame("2026-02", "2026-04", WidgetTimeFrame.Mode.SlidingWindow)))
}
