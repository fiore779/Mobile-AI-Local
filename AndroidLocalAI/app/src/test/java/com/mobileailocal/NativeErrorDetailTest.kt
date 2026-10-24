package com.mobileailocal

import com.mobileailocal.domain.ErrorDetailText
import com.mobileailocal.domain.LoadCause
import com.mobileailocal.domain.LoadCauseGuess
import com.mobileailocal.domain.NativeError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeErrorDetailTest {
    @Test fun parseWithDetail() {
        assertEquals(NativeError.LoadFile, NativeError.parse("E_LOAD_FILE:unknown model architecture: 'foo'"))
        assertNull(NativeError.parse("E_BOGUS:x"))
    }

    @Test fun detailExtracted() {
        assertEquals("unknown model architecture: 'foo'", NativeError.detail("E_LOAD_FILE:unknown model architecture: 'foo'"))
        assertEquals("a\nb", NativeError.detail("E_LOAD_FILE:a\n\n b \n"))
        assertNull(NativeError.detail("E_LOAD_FILE"))
        assertNull(NativeError.detail("E_LOAD_FILE:  "))
        assertNull(NativeError.detail("other: text"))
        assertNull(NativeError.detail(null))
    }

    @Test fun detailHidesPathAndLimits() {
        assertEquals("failed to load <path> now", NativeError.detail("E_LOAD_FILE:failed to load /data/user/0/x/files/m.gguf now"))
        assertEquals("open <path> x", NativeError.detail("E_LOAD_FILE:open \\Users\\a\\m.gguf x"))
        assertEquals(301, NativeError.detail("E_LOAD_FILE:" + "a".repeat(400))!!.length)
        val many = (1..15).joinToString("\n") { "line$it" }
        val lines = NativeError.detail("E_LOAD_FILE:$many")!!.lines()
        assertEquals(10, lines.size)
        assertEquals("line15", lines.last())
    }

    @Test fun causeGuess() {
        assertEquals(LoadCause.UnknownArchitecture, LoadCauseGuess.cause("unknown model architecture: 'nemotron_x'"))
        assertEquals(LoadCause.OutOfMemory, LoadCauseGuess.cause("ggml_backend_cpu_buffer_type_alloc_buffer: failed to allocate buffer"))
        assertEquals(LoadCause.Corrupt, LoadCauseGuess.cause("gguf_init_from_file_impl: invalid magic characters"))
        assertEquals(LoadCause.Unsupported, LoadCauseGuess.cause("done_getting_tensors: missing tensor 'x'"))
        assertNull(LoadCauseGuess.cause("something odd"))
        assertNull(LoadCauseGuess.cause(null))
    }

    @Test fun architectureName() {
        assertEquals("nemotron_x", LoadCauseGuess.architecture("error: unknown model architecture: 'nemotron_x'"))
        assertNull(LoadCauseGuess.architecture("unknown model architecture: ''"))
        assertNull(LoadCauseGuess.architecture(null))
    }

    @Test fun joinSplit() {
        assertEquals("s" to null, ErrorDetailText.split(ErrorDetailText.join("s", null)))
        assertEquals("s" to "d1\nd2", ErrorDetailText.split(ErrorDetailText.join("s", "d1\nd2")))
    }
}
