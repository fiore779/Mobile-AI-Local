package com.mobileailocal

import com.mobileailocal.domain.loadingPercent
import org.junit.Assert.assertEquals
import org.junit.Test

class LoadingProgressTest {
    @Test fun normal() { assertEquals(0, loadingPercent(0f)); assertEquals(42, loadingPercent(0.426f)); assertEquals(100, loadingPercent(1f)) }
    @Test fun clamps() { assertEquals(0, loadingPercent(-1f)); assertEquals(100, loadingPercent(2f)); assertEquals(0, loadingPercent(Float.NaN)) }
}
