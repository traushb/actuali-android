package io.github.traushb.actuali.views.components

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusToneTest {
    @Test
    fun signedAmountsTakeTheirDirectionsToneAndZeroIsGray() {
        assertEquals(StatusTone.Positive, StatusTone.of(1))
        assertEquals(StatusTone.Negative, StatusTone.of(-1))
        assertEquals(StatusTone.Neutral, StatusTone.of(0))
    }

    /** Hidden balances get one gray fill, so the color can't give away what the mask hides. */
    @Test
    fun maskedAmountsAreAlwaysGray() {
        for (amount in listOf(-1L, 0L, 1L)) assertEquals(StatusTone.Neutral, StatusTone.of(amount, masked = true))
    }
}
