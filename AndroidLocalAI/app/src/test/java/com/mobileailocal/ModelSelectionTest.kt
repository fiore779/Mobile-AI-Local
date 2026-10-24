package com.mobileailocal

import com.mobileailocal.domain.CloudModelRef
import com.mobileailocal.domain.ModelSelection
import com.mobileailocal.domain.decodeModelSelection
import com.mobileailocal.domain.encode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelSelectionTest {
    @Test fun localIsUnchanged() {
        val id = "owner_repo__file.Q4_K_M.gguf"
        assertEquals(id, ModelSelection.Local(id).encode())
        assertEquals(ModelSelection.Local(id), decodeModelSelection(id))
    }

    @Test fun cloudRoundTrip() {
        for (model in listOf("gpt-4o", "models/gemini:flash", "a/b/c", "x:y")) {
            val sel = ModelSelection.Cloud(CloudModelRef("1234-abcd", model))
            assertEquals(sel, decodeModelSelection(sel.encode()))
        }
        assertEquals("cloud:i1/m/x", ModelSelection.Cloud(CloudModelRef("i1", "m/x")).encode())
    }

    @Test fun nullEmptyAndInvalid() {
        assertNull(decodeModelSelection(null))
        assertNull(decodeModelSelection(""))
        assertNull(decodeModelSelection("cloud:"))
        assertNull(decodeModelSelection("cloud:onlyid"))
        assertNull(decodeModelSelection("cloud:/model"))
        assertNull(decodeModelSelection("cloud:id/"))
    }
}
