package com.mobileailocal

import com.mobileailocal.data.KeyBlobCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class KeyBlobCodecTest {
    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val key = newKey()
    private val codec = KeyBlobCodec { key }

    @Test fun roundTripAndNoPlaintext() {
        val secret = "sk-test-SECRET-1234567890"
        val blob = codec.encrypt("u1", "i1", secret)
        assertEquals(KeyBlobCodec.VERSION, blob[0])
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("SECRET"))
        assertEquals(secret, codec.decrypt("u1", "i1", blob))
    }

    @Test fun differentIvEachTime() {
        assertNotEquals(codec.encrypt("u", "i", "k").toList(), codec.encrypt("u", "i", "k").toList())
    }

    @Test fun swappedFileFailsToDecrypt() {
        val blob = codec.encrypt("u1", "i1", "k")
        assertNull(codec.decrypt("u1", "i2", blob)) // 別の連携
        assertNull(codec.decrypt("u2", "i1", blob)) // 別のアカウント
    }

    @Test fun tamperedOrTruncatedOrWrongKey() {
        val blob = codec.encrypt("u", "i", "k")
        val bad = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(codec.decrypt("u", "i", bad))
        assertNull(codec.decrypt("u", "i", blob.copyOf(5)))
        assertNull(codec.decrypt("u", "i", ByteArray(0)))
        assertNull(codec.decrypt("u", "i", blob.copyOf().also { it[0] = 9 }))
        assertNull(KeyBlobCodec { newKey() }.decrypt("u", "i", blob))
    }

    @Test fun missingKeyDoesNotThrow() {
        val blob = codec.encrypt("u", "i", "k")
        assertNull(KeyBlobCodec { throw IllegalStateException("no key") }.decrypt("u", "i", blob))
    }

    @Test fun fileNameHidesUid() {
        val n = KeyBlobCodec.fileName("user@example.com", "int-1")
        assertFalse("user" in n)
        assertTrue(n.endsWith("_int-1.bin"))
        assertEquals(32 + 1 + "int-1.bin".length, n.length)
        assertNotEquals(n, KeyBlobCodec.fileName("other", "int-1"))
    }
}
