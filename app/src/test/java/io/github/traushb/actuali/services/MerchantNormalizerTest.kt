package io.github.traushb.actuali.services

import org.junit.Assert.assertEquals
import org.junit.Test

class MerchantNormalizerTest {
    private fun normalize(raw: String) = MerchantNormalizer.normalize(raw)

    @Test
    fun stripsSquarePrefix() {
        assertEquals("Blue Bottle", normalize("SQ *BLUE BOTTLE"))
        assertEquals("Blue Bottle", normalize("sq *blue bottle"))
    }

    @Test
    fun stripsToastPrefix() {
        assertEquals("Dough", normalize("TST* DOUGH"))
        assertEquals("Dough", normalize("TST *DOUGH"))
    }

    @Test
    fun stripsShopifyPrefix() = assertEquals("Cool Store", normalize("SP COOL STORE"))

    @Test
    fun stripsPaypalPrefix() = assertEquals("Merchant", normalize("PAYPAL *MERCHANT"))

    @Test
    fun stripsTrailingStoreNumber() {
        assertEquals("Blue Bottle", normalize("BLUE BOTTLE #4"))
        assertEquals("Starbucks", normalize("STARBUCKS #1234"))
    }

    @Test
    fun combinesPrefixAndStoreNumber() = assertEquals("Blue Bottle", normalize("SQ *BLUE BOTTLE #4"))

    @Test
    fun trimsWhitespace() {
        assertEquals("Blue Bottle", normalize("  BLUE BOTTLE  "))
        assertEquals("Blue Bottle", normalize("SQ *  BLUE BOTTLE"))
    }

    @Test
    fun passesThroughCleanInput() = assertEquals("Blue Bottle", normalize("Blue Bottle"))

    @Test
    fun handlesEmptyAndWhitespaceOnly() {
        assertEquals("", normalize(""))
        assertEquals("", normalize("   "))
    }

    @Test
    fun preservesInternalApostrophesAsLocalizedCapitalized() = assertEquals("Mcdonald's", normalize("MCDONALD'S #42"))
}
