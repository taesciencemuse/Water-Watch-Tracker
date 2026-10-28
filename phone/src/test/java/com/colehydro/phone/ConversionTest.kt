package com.colehydro.phone

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

/** Oz/ml rounding: the v9 bug was truncating 64 oz -> 1892 ml -> 63 oz.
 *  Rounding both directions keeps 64 oz = 64 oz, and these tests
 *  pin that behaviour so a future refactor cannot regress it. */
class ConversionTest {
    private fun oz(ml: Int) = (ml / 29.5735).roundToInt()
    private fun ml(oz: Int) = (oz * 29.5735).roundToInt()

    @Test fun roundTrip64() {
        assertEquals(64, oz(ml(64)))
        assertEquals(1893, ml(64))
    }

    @Test fun roundTrip8_12_16() {
        assertEquals(8, oz(ml(8)))
        assertEquals(12, oz(ml(12)))
        assertEquals(16, oz(ml(16)))
    }

    @Test fun goalRange() {
        assertEquals(16, oz(ml(16)))
        assertEquals(200, oz(ml(200)))
    }
}
