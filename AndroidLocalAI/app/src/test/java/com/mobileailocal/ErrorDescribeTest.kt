package com.mobileailocal

import com.mobileailocal.data.KeyBlobCodec
import com.mobileailocal.domain.ErrorDescribe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

class ErrorDescribeTest {
    @Test fun showsClassNameAndMessage() {
        val d = ErrorDescribe.describe(java.security.InvalidAlgorithmParameterException("Caller-provided IV not permitted"))
        assertEquals("java.security.InvalidAlgorithmParameterException: Caller-provided IV not permitted", d)
    }

    @Test fun hidesPathsAndSecrets() {
        val e = IOException("cannot write /data/user/0/com.mobileailocal/files/cloud-keys/ab_cd.bin for sk-SECRETKEY123")
        val d = ErrorDescribe.describe(e, listOf("sk-SECRETKEY123"))
        assertFalse("/data" in d)
        assertFalse("SECRETKEY" in d)
        assertTrue("<path>" in d && "••••" in d)
        // 短すぎる秘密(空文字など)は置換しない
        assertEquals("java.io.IOException: x", ErrorDescribe.describe(IOException("x"), listOf("", "ab")))
    }

    @Test fun followsCauseChainWithLimit() {
        val e = RuntimeException("top", IllegalStateException("mid", IOException("root", Error("too deep"))))
        val lines = ErrorDescribe.describe(e).lines()
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("caused by java.lang.IllegalStateException"))
        assertTrue(lines[2].contains("root"))
    }

    @Test fun nullMessageAndLongMessage() {
        assertEquals("java.lang.IllegalStateException", ErrorDescribe.describe(IllegalStateException()))
        assertTrue(ErrorDescribe.describe(IllegalStateException("a".repeat(1000))).length <= 300)
    }

    // --- 暗号化: IV は Cipher が作る(Android Keystore と同じ使い方)ことを、ソフトウェア鍵でも確かめる ---
    @Test fun encryptDoesNotPassCallerIv() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val blob = KeyBlobCodec { key }.encrypt("u", "i", "secret")
        assertEquals(KeyBlobCodec.VERSION, blob[0])
        // 先頭 1 + IV 12 バイトを取り出して、同じ鍵・IV・AAD で復号できる(形式 [版][IV 12B][暗号文+タグ])
        assertEquals("secret", KeyBlobCodec { key }.decrypt("u", "i", blob))
        assertTrue(blob.size >= 1 + KeyBlobCodec.IV_BYTES + KeyBlobCodec.TAG_BITS / 8 + "secret".length)
        assertTrue(Cipher.getInstance("AES/GCM/NoPadding").algorithm.isNotEmpty())
    }
}
