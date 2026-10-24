package com.mobileailocal

import com.mobileailocal.data.RemoteFile
import com.mobileailocal.data.RemoteModel
import com.mobileailocal.domain.Fit
import com.mobileailocal.domain.ModelRecommender
import com.mobileailocal.domain.Recommendation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRecommenderTest {
    private val mb = 1024L * 1024L
    private fun f(name: String, sizeMb: Long, repo: String = "a/b") = RemoteFile(repo, name, sizeMb * mb)

    @Test fun excludesUnwantedQuants() {
        val files = listOf(
            f("m-Q4_K_M.gguf", 1000), f("m-Q8_0.gguf", 2000), f("m-F16.gguf", 3000),
            f("m-IQ3_XXS.gguf", 600), f("mmproj-F16.gguf", 100),
        )
        val (p, _) = ModelRecommender.pickFile(files, 100000)!!
        assertEquals("m-Q4_K_M.gguf", p.fileName)
    }

    @Test fun fitComfortableOrTight() {
        val q4 = listOf(f("m-Q4_K_M.gguf", 1000))
        // 目安 1200 → 1000 は 83% で Tight
        assertEquals(Fit.Tight, ModelRecommender.pickFile(q4, 1200)!!.second)
        // 目安 1500 → 67% で Comfortable
        assertEquals(Fit.Comfortable, ModelRecommender.pickFile(q4, 1500)!!.second)
    }

    @Test fun nullWhenTooBig() = assertNull(ModelRecommender.pickFile(listOf(f("m-Q4_K_M.gguf", 1000)), 900))

    @Test fun lowQuantsAndUnknownOnly() {
        val files = listOf(f("m-Q2_K.gguf", 10), f("m-IQ2_XS.gguf", 10), f("m-IQ1_S.gguf", 10), f("model.gguf", 10))
        assertNull(ModelRecommender.pickFile(files, 100000))
    }

    @Test fun allowedVariants() {
        assertNotNull(ModelRecommender.pickFile(listOf(f("m-IQ3_XXS.gguf", 10)), 1000))
        assertNotNull(ModelRecommender.pickFile(listOf(f("m-UD-Q4_K_XL.gguf", 10)), 1000))
    }

    @Test fun ignoresZeroSizeAndTieBreaks() {
        assertNull(ModelRecommender.pickFile(listOf(f("m-Q4_K_M.gguf", 0)), 1000))
        val r = ModelRecommender.pickFile(listOf(f("m-Q5_K_M.gguf", 100), f("m-Q4_K_M.gguf", 100)), 1000)!!
        assertEquals("m-Q4_K_M.gguf", r.first.fileName)
    }

    @Test fun paramsBillion() {
        assertEquals(4.0, ModelRecommender.paramsBillionOf("Qwen3-4B-GGUF")!!, 0.0)
        assertEquals(0.6, ModelRecommender.paramsBillionOf("Qwen3-0.6B-GGUF")!!, 0.0)
        assertEquals(8.0, ModelRecommender.paramsBillionOf("Llama-3.1-8B-Instruct")!!, 0.0)
        assertEquals(4.0, ModelRecommender.paramsBillionOf("gemma-3n-E4B-it")!!, 0.0)
        assertEquals(7.0, ModelRecommender.paramsBillionOf("mistral-7b")!!, 0.0)
        assertNull(ModelRecommender.paramsBillionOf("foo-bar"))
    }

    @Test fun clearlyTooBig() {
        assertTrue(ModelRecommender.clearlyTooBig("x-70B", 3650))
        assertFalse(ModelRecommender.clearlyTooBig("x-4B", 3650))
        assertFalse(ModelRecommender.clearlyTooBig("foo-bar", 10))
    }

    private fun rec(name: String, sizeMb: Long) =
        Recommendation(RemoteModel(name, 0, 0), f("$name-Q4_K_M.gguf", sizeMb, name), Fit.Comfortable)

    @Test fun rankKeepsOrderAndDemotesSmall() {
        val r = ModelRecommender.rank(listOf(rec("tiny", 10), rec("a", 500), rec("b", 600)), 1000)
        assertEquals(listOf("a", "b", "tiny"), r.map { it.model.repoId })
    }

    @Test fun rankLimitsAndEmpty() {
        assertEquals(10, ModelRecommender.rank(List(11) { rec("m$it", 500) }, 1000).size)
        assertTrue(ModelRecommender.rank(emptyList(), 1000).isEmpty())
    }
}
