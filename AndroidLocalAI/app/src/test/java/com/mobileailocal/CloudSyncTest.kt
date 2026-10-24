package com.mobileailocal

import com.mobileailocal.data.IntegrationDoc
import com.mobileailocal.domain.CloudIntegration
import com.mobileailocal.domain.LocalIntegration
import com.mobileailocal.domain.ProviderType
import com.mobileailocal.domain.SyncState
import com.mobileailocal.domain.planSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CloudSyncTest {
    private fun item(id: String, updated: Long, url: String = "https://api.example.com/v1", name: String = id) =
        CloudIntegration(id, ProviderType.OpenAi, name, url, listOf("m"), "m", 1, updated)

    private fun local(id: String, updated: Long, state: SyncState, url: String = "https://api.example.com/v1") =
        LocalIntegration(item(id, updated, url), state)

    // --- 同期の計画 ---
    @Test fun newLocalIsUploaded() {
        val plan = planSync(listOf(local("a", 5, SyncState.Local)), emptyList())
        assertEquals(listOf("a"), plan.upload.map { it.id })
        assertTrue(plan.saveLocal.isEmpty() && plan.deleteLocal.isEmpty() && plan.deleteRemote.isEmpty())
    }

    @Test fun remoteOnlyIsImported() {
        val plan = planSync(emptyList(), listOf(item("r", 3)))
        assertEquals(listOf("r"), plan.saveLocal.map { it.id })
        assertTrue(plan.upload.isEmpty())
    }

    @Test fun conflictLastWriteWins() {
        // ローカルが新しい → 送る
        assertEquals(listOf("a"), planSync(listOf(local("a", 10, SyncState.Local)), listOf(item("a", 5))).upload.map { it.id })
        // サーバーが新しい → 取り込む(ローカルの未送信の変更は負ける)
        val p = planSync(listOf(local("a", 5, SyncState.Local)), listOf(item("a", 10)))
        assertEquals(listOf(10L), p.saveLocal.map { it.updatedAt })
        assertTrue(p.upload.isEmpty())
        // 同期済み同士で差が無ければ何もしない
        val same = planSync(listOf(local("a", 5, SyncState.Synced)), listOf(item("a", 5)))
        assertTrue(same.upload.isEmpty() && same.saveLocal.isEmpty() && same.deleteLocal.isEmpty())
        // 同期済みでサーバーのほうが新しい
        assertEquals(listOf("a"), planSync(listOf(local("a", 5, SyncState.Synced)), listOf(item("a", 9))).saveLocal.map { it.id })
    }

    @Test fun deletedElsewhereRemovesLocalSyncedRow() {
        val plan = planSync(listOf(local("gone", 5, SyncState.Synced), local("fresh", 5, SyncState.Local)), emptyList())
        assertEquals(listOf("gone"), plan.deleteLocal)
        assertEquals(listOf("fresh"), plan.upload.map { it.id }) // 未送信の新規は消さない
    }

    @Test fun localDeletionIsPropagated() {
        val plan = planSync(listOf(local("d", 5, SyncState.Deleted)), listOf(item("d", 5)))
        assertEquals(listOf("d"), plan.deleteRemote)
        assertTrue(plan.saveLocal.isEmpty()) // 削除待ちの行は取り込み直さない
        // サーバーに無ければ、削除待ちの行だけ片付ける
        assertEquals(listOf("d"), planSync(listOf(local("d", 5, SyncState.Deleted)), emptyList()).deleteLocal)
    }

    @Test fun nonSyncableStaysOnDevice() {
        val plan = planSync(
            listOf(
                local("http", 5, SyncState.Local, "http://192.168.0.2/v1"),
                local("query", 5, SyncState.Local, "https://x.com/v1?key=1"),
            ),
            emptyList(),
        )
        assertTrue(plan.upload.isEmpty())
        assertTrue(plan.deleteLocal.isEmpty())
    }

    // --- Firestore 文書の変換 ---
    @Test fun docRoundTripHasOnlyAllowedFields() {
        val i = item("a", 7).copy(lastModel = null)
        val map = IntegrationDoc.toMap(i)
        assertTrue(IntegrationDoc.ALLOWED_FIELDS.containsAll(map.keys))
        assertFalse("lastModel" in map)
        assertEquals(1L, map["schemaVersion"])
        assertEquals(i, IntegrationDoc.fromMap("a", map))
        val withLast = IntegrationDoc.toMap(item("b", 7))
        assertEquals("m", withLast["lastModel"])
    }

    @Test fun docNeverHasSecretFields() {
        val keys = IntegrationDoc.ALLOWED_FIELDS.map { it.lowercase() }
        for (bad in listOf("key", "apikey", "token", "secret", "password", "message", "content", "history")) {
            assertTrue(bad, keys.none { it == bad })
        }
    }

    @Test fun invalidDocsAreIgnored() {
        val ok = IntegrationDoc.toMap(item("a", 7))
        assertNull(IntegrationDoc.fromMap("a", null))
        assertNull(IntegrationDoc.fromMap("a", ok + ("type" to "Unknown")))
        assertNull(IntegrationDoc.fromMap("a", ok - "displayName"))
        assertNull(IntegrationDoc.fromMap("a", ok + ("displayName" to "x".repeat(41))))
        assertNull(IntegrationDoc.fromMap("a", ok + ("baseUrl" to "http://192.168.0.2/v1")))
        assertNull(IntegrationDoc.fromMap("a", ok + ("enabledModels" to emptyList<String>())))
        assertNull(IntegrationDoc.fromMap("a", ok - "updatedAt"))
        // 11 件以上は 10 件に丸める
        val many = IntegrationDoc.fromMap("a", ok + ("enabledModels" to (1..12).map { "m$it" }))!!
        assertEquals(10, many.enabledModels.size)
    }

    // --- セキュリティルールのファイルと許可項目の一致 ---
    @Test fun rulesFileMatchesAllowedFieldsAndRestrictsToOwner() {
        val rules = File("../firestore.rules").readText()
        for (f in IntegrationDoc.ALLOWED_FIELDS) assertTrue("rules に $f が無い", "'$f'" in rules)
        assertTrue("request.auth.uid == u" in rules)
        assertTrue("match /users/{uid}/integrations/{integrationId}" in rules)
        assertTrue("allow read, write: if false;" in rules) // それ以外は全拒否
        // 秘密らしい項目名を許可していない
        for (bad in listOf("apiKey", "token", "secret", "password")) assertFalse(bad, bad in rules)
    }
}
