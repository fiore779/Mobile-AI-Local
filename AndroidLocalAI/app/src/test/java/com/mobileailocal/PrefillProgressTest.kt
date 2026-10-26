package com.mobileailocal

import com.mobileailocal.domain.prefillPercentOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefillProgressTest {
    @Test fun startsAtZeroAndRises() {
        assertEquals(0, prefillPercentOf(0, 1000))
        assertEquals(12, prefillPercentOf(128, 1000))
        assertEquals(99, prefillPercentOf(999, 1000))
    }

    @Test fun finishedOrEmptyHidesTheLine() {
        assertNull(prefillPercentOf(1000, 1000))
        assertNull(prefillPercentOf(1200, 1000))
        assertNull(prefillPercentOf(0, 0))
        assertNull(prefillPercentOf(5, -1))
    }

    @Test fun negativeIsClampedToZero() = assertEquals(0, prefillPercentOf(-5, 100))
}
