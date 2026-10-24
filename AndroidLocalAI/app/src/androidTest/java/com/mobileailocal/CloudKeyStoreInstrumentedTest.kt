package com.mobileailocal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobileailocal.data.CloudKeyStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * 実際の Android Keystore で、API キーの保存 → 復号のラウンドトリップを確かめる(端末・エミュレーター用)。
 * アプリ本体の鍵(別名 mal_cloud_key_v1)とファイル(cloud-keys/)には触れない。テスト専用の別名とディレクトリを使う。
 */
@RunWith(AndroidJUnit4::class)
class CloudKeyStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAlias = "mal_cloud_key_test_instrumented"
    private val testDir = "cloud-keys-test"
    private lateinit var store: CloudKeyStore
    private val uid = "test-uid-instrumented"

    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun cleanUp() {
        runBlocking { if (::store.isInitialized) store.deleteAllFor(uid) }
        File(context.filesDir, testDir).deleteRecursively()
        runCatching { keystore().deleteEntry(testAlias) }
    }

    @Before fun setUp() {
        cleanUp()
        store = CloudKeyStore(context, alias = testAlias, dirName = testDir)
    }

    @After fun tearDown() = cleanUp()

    @Test fun putThenGetRoundTrip() = runBlocking {
        store.put(uid, "i1", "sk-test-SECRET-0123456789")
        assertTrue(store.has(uid, "i1"))
        assertEquals("sk-test-SECRET-0123456789", store.get(uid, "i1"))
    }

    @Test fun overwriteAndMultipleIntegrations() = runBlocking {
        store.put(uid, "i1", "first")
        store.put(uid, "i1", "second")
        store.put(uid, "i2", "other")
        assertEquals("second", store.get(uid, "i1"))
        assertEquals("other", store.get(uid, "i2"))
    }

    @Test fun fileDoesNotContainPlaintext() = runBlocking {
        store.put(uid, "i1", "PLAINTEXT-MARKER-KEY")
        val dir = File(context.filesDir, testDir)
        val bytes = dir.listFiles()!!.filter { it.name.endsWith(".bin") }.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertFalse("PLAINTEXT-MARKER-KEY" in bytes)
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun swappedFileFailsToDecrypt() = runBlocking {
        store.put(uid, "i1", "k1")
        val dir = File(context.filesDir, testDir)
        val a = dir.listFiles()!!.first { it.name.endsWith("_i1.bin") }
        a.copyTo(File(dir, a.name.replace("_i1.bin", "_i2.bin")), overwrite = true)
        assertNull(store.get(uid, "i2"))
    }

    @Test fun recoversAfterKeystoreKeyIsRemoved() = runBlocking {
        store.put(uid, "i1", "before")
        // 鍵が失われた(端末の設定変更・Keystore の初期化など)ことを再現する(テスト用の別名だけを消す)
        keystore().deleteEntry(testAlias)
        assertNull(store.get(uid, "i1")) // 復号できない → null(アプリは落ちない)
        store.put(uid, "i1", "after") // 新しい鍵で保存し直せる
        assertEquals("after", store.get(uid, "i1"))
    }

    @Test fun deleteRemovesKey() = runBlocking {
        store.put(uid, "i1", "k")
        store.delete(uid, "i1")
        assertFalse(store.has(uid, "i1"))
        assertNull(store.get(uid, "i1"))
    }

    /** 鍵がまだ無い状態で同時に保存しても、鍵が二重に作られず、全部復号できる(排他の確認)。 */
    @Test fun concurrentPutsCreateOneKey() = runBlocking {
        val ids = (1..12).map { "c$it" }
        ids.map { id -> async(kotlinx.coroutines.Dispatchers.Default) { store.put(uid, id, "secret-$id") } }.awaitAll()
        ids.forEach { assertEquals("secret-$it", store.get(uid, it)) }
    }

    /** テスト専用の別名を使い、アプリ本体の鍵・ファイルには触れていないこと。 */
    @Test fun doesNotTouchProductionAlias() = runBlocking {
        val prod = CloudKeyStore.DEFAULT_ALIAS
        assertNotEquals(prod, testAlias)
        val before = keystore().containsAlias(prod)
        val prodDir = File(context.filesDir, "cloud-keys")
        val filesBefore = prodDir.list().orEmpty().sorted()
        store.put(uid, "i1", "k")
        keystore().deleteEntry(testAlias)
        assertEquals(before, keystore().containsAlias(prod)) // 本番の鍵には触れていない
        assertEquals(filesBefore, prodDir.list().orEmpty().sorted()) // 本番のキーのファイルも増減していない
    }
}
