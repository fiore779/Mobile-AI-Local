package com.mobileailocal

import com.mobileailocal.data.KeyBlobCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.InvalidKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** 鍵の作り直しとファイル削除は「鍵が永久に無効になった」ときだけ、という方針の確認。 */
class KeyInvalidationTest {
    private class Invalidated(cause: Throwable? = null) : InvalidKeyException("invalidated", cause)

    private val good: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val blob = KeyBlobCodec { good }.encrypt("u", "i", "secret")

    private fun codec(throwing: () -> Throwable) =
        KeyBlobCodec(key = { throw throwing() }, isKeyInvalidated = { it is Invalidated })

    @Test fun genericInvalidKeyExceptionIsTransient() {
        // 汎用の InvalidKeyException では、ファイルを消さない(Transient)
        val r = codec { InvalidKeyException("some device quirk") }.decryptDetailed("u", "i", blob)
        assertEquals(KeyBlobCodec.Decrypted.Transient, r)
    }

    @Test fun permanentlyInvalidatedIsCorrupt() {
        assertEquals(KeyBlobCodec.Decrypted.Corrupt, codec { Invalidated() }.decryptDetailed("u", "i", blob))
        // 原因の連鎖の中にあっても判定する
        val wrapped = codec { IllegalStateException("wrap", Invalidated()) }
        assertEquals(KeyBlobCodec.Decrypted.Corrupt, wrapped.decryptDetailed("u", "i", blob))
    }

    @Test fun isInvalidatedOnlyForTheSpecificException() {
        val c = KeyBlobCodec(key = { good }, isKeyInvalidated = { it is Invalidated })
        assertTrue(c.isInvalidated(Invalidated()))
        assertTrue(c.isInvalidated(RuntimeException(Invalidated())))
        assertFalse(c.isInvalidated(InvalidKeyException("generic")))
        assertFalse(c.isInvalidated(IllegalStateException("x")))
        // 既定の判定は本物の KeyPermanentlyInvalidatedException だけ(汎用の InvalidKeyException は対象外)
        assertFalse(KeyBlobCodec { good }.isInvalidated(InvalidKeyException("generic")))
    }

    @Test fun badTagStaysCorruptAndOtherErrorsTransient() {
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertEquals(KeyBlobCodec.Decrypted.Corrupt, KeyBlobCodec { other }.decryptDetailed("u", "i", blob))
        assertEquals(KeyBlobCodec.Decrypted.Transient, codec { IllegalStateException("busy") }.decryptDetailed("u", "i", blob))
    }
}
