package com.mobileailocal

import com.mobileailocal.data.KeyBlobCodec
import com.mobileailocal.domain.ChatMessage
import com.mobileailocal.domain.CloudInputs
import com.mobileailocal.domain.ProviderType
import com.mobileailocal.domain.Role
import com.mobileailocal.domain.normalizeForCloud
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

class CloudFixesTest {
    private fun m(role: Role, s: String) = ChatMessage(role, s)

    // --- 空の返答の除去と同じ役割の連結 ---
    @Test fun dropsBlankAndMergesSameRole() {
        val out = normalizeForCloud(
            listOf(
                m(Role.System, "sys"),
                m(Role.User, "q1"), m(Role.Assistant, ""), // 失敗して空のまま残った返答
                m(Role.User, "q2"), m(Role.Assistant, "  "),
                m(Role.User, "q3"),
            ),
        )
        assertEquals(listOf(Role.System, Role.User), out.map { it.role })
        assertEquals("q1\n\nq2\n\nq3", out[1].content)
    }

    @Test fun keepsAlternatingMessagesAndSystem() {
        val list = listOf(m(Role.System, "s"), m(Role.User, "a"), m(Role.Assistant, "b"), m(Role.User, "c"))
        assertEquals(list, normalizeForCloud(list))
        assertEquals(emptyList<ChatMessage>(), normalizeForCloud(emptyList()))
        // System は空でも消さず、連結もしない
        assertEquals(2, normalizeForCloud(listOf(m(Role.System, ""), m(Role.System, "x"))).size)
    }

    // --- 同意の単位 ---
    @Test fun consentScopeIsHostForCustomOnly() {
        assertEquals("api.example.com", CloudInputs.consentScope(ProviderType.OpenAiCompatCustom, "https://API.example.com:8443/v1"))
        assertEquals("", CloudInputs.consentScope(ProviderType.OpenAi, "https://api.openai.com/v1"))
        assertEquals("", CloudInputs.consentScope(ProviderType.OpenAiCompatCustom, "not a url"))
        assertEquals("::1", CloudInputs.hostOfUrl("https://[::1]:8080/v1"))
        assertNull(CloudInputs.hostOfUrl("ftp://x"))
    }

    // --- 復号の失敗の種類 ---
    @Test fun corruptVsTransient() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val codec = KeyBlobCodec { key }
        val blob = codec.encrypt("u", "i", "secret")
        assertEquals(KeyBlobCodec.Decrypted.Ok("secret"), codec.decryptDetailed("u", "i", blob))
        // 壊れている・別の連携・版違い・短すぎる → 消してよい
        assertEquals(KeyBlobCodec.Decrypted.Corrupt, codec.decryptDetailed("u", "other", blob))
        assertEquals(KeyBlobCodec.Decrypted.Corrupt, codec.decryptDetailed("u", "i", blob.copyOf(4)))
        assertEquals(KeyBlobCodec.Decrypted.Corrupt, codec.decryptDetailed("u", "i", blob.copyOf().also { it[0] = 7 }))
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertEquals(KeyBlobCodec.Decrypted.Corrupt, KeyBlobCodec { otherKey }.decryptDetailed("u", "i", blob))
        // Keystore の一時的な失敗 → 消さない
        val flaky = KeyBlobCodec { throw IllegalStateException("keystore busy") }
        assertEquals(KeyBlobCodec.Decrypted.Transient, flaky.decryptDetailed("u", "i", blob))
        assertNull(flaky.decrypt("u", "i", blob))
        assertTrue(AEADBadTagException::class.java.name.isNotEmpty())
    }
}
