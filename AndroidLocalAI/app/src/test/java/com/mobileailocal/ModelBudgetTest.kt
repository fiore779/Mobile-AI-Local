package com.mobileailocal

import com.mobileailocal.domain.ModelBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelBudgetTest {
    private val arm = listOf("arm64-v8a")
    private fun b(t: Long, f: Long, app: Long = 0, ctx: Int = 4096, abis: List<String> = arm) =
        ModelBudget.budgetMb(t, f, app, ctx, abis)

    @Test fun tableRows() {
        assertEquals(3650L, b(11300, 5000))
        assertEquals(2375L, b(7400, 3500))
        assertEquals(930L, b(3600, 1800))
    }

    @Test fun largerContextReducesBoth() {
        assertEquals(3450L, b(11300, 5000, ctx = 8192) )
        // total が効く場合
        assertEquals(4850L, b(11300, 100000, ctx = 8192))
        assertEquals(5050L, b(11300, 100000))
    }

    @Test fun minOfTwoLimits() {
        assertEquals(5050L, b(11300, 100000)) // limitTotal
        assertEquals(3650L, b(11300, 5000)) // limitAvail
    }

    @Test fun appMbRaisesBudget() {
        assertTrue(b(11300, 5000, app = 1000) > b(11300, 5000, app = 0))
    }

    @Test fun neverNegative() = assertEquals(0L, b(500, 100))

    @Test fun abi() {
        assertEquals(1825L, b(11300, 5000, abis = listOf("x86_64")))
        assertEquals(3650L, b(11300, 5000, abis = listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(1825L, b(11300, 5000, abis = emptyList()))
    }
}
