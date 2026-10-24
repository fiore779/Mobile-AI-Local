package com.mobileailocal.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.mobileailocal.domain.CloudIntegration
import com.mobileailocal.domain.CloudInputs
import com.mobileailocal.domain.ProviderType

/** アカウント別の連携メタデータの保存先。キー・トークン・会話内容は持たない。 */
interface CloudAccountStore {
    suspend fun load(uid: String): List<CloudIntegration>
    suspend fun upsert(uid: String, item: CloudIntegration)
    suspend fun delete(uid: String, integrationId: String)
    suspend fun deleteAll(uid: String)
}

/**
 * Firestore の文書との変換(Firebase に依存しない純粋なロジック)。
 * 文書の項目は firestore.rules の許可リストと一致させること(キー・トークン・会話の項目は作らない)。
 */
object IntegrationDoc {
    const val SCHEMA_VERSION = 1L
    val ALLOWED_FIELDS = setOf(
        "type", "displayName", "baseUrl", "enabledModels", "lastModel", "createdAt", "updatedAt", "schemaVersion",
    )

    fun toMap(i: CloudIntegration): Map<String, Any> = buildMap {
        put("type", i.type.name)
        put("displayName", i.displayName)
        put("baseUrl", i.baseUrl)
        put("enabledModels", i.enabledModels)
        i.lastModel?.let { put("lastModel", it) }
        put("createdAt", i.createdAt)
        put("updatedAt", i.updatedAt)
        put("schemaVersion", SCHEMA_VERSION)
    }

    /** 読めない・不正な文書(未知の種別、必須項目なし、https でない接続先)は null にして取り込まない。 */
    fun fromMap(id: String, m: Map<String, Any?>?): CloudIntegration? {
        if (m == null) return null
        val type = ProviderType.entries.firstOrNull { it.name == m["type"] } ?: return null
        val name = (m["displayName"] as? String)?.takeIf { it.length in 1..CloudInputs.MAX_DISPLAY_NAME } ?: return null
        val url = (m["baseUrl"] as? String)?.takeIf { CloudInputs.syncable(it) } ?: return null
        val models = (m["enabledModels"] as? List<*>)?.filterIsInstance<String>()
            ?.filter { it.isNotEmpty() }?.take(CloudInputs.MAX_MODELS)?.takeIf { it.isNotEmpty() } ?: return null
        val created = (m["createdAt"] as? Number)?.toLong() ?: return null
        val updated = (m["updatedAt"] as? Number)?.toLong() ?: return null
        return CloudIntegration(id, type, name, url, models, m["lastModel"] as? String, created, updated)
    }
}

/** Firestore の users/{uid}/integrations/{integrationId}。ネットワークが無いときは例外(呼び出し側が無視する)。 */
class FirestoreAccountStore(private val db: FirebaseFirestore) : CloudAccountStore {
    private fun col(uid: String) = db.collection("users").document(uid).collection("integrations")

    // 古いキャッシュで「他の端末で削除された」と誤判定しないよう、読み込みは必ずサーバーから
    override suspend fun load(uid: String): List<CloudIntegration> =
        col(uid).get(Source.SERVER).awaitTask().documents.mapNotNull { IntegrationDoc.fromMap(it.id, it.data) }

    override suspend fun upsert(uid: String, item: CloudIntegration) {
        col(uid).document(item.id).set(IntegrationDoc.toMap(item)).awaitTask()
    }

    override suspend fun delete(uid: String, integrationId: String) {
        col(uid).document(integrationId).delete().awaitTask()
    }

    override suspend fun deleteAll(uid: String) {
        val docs = col(uid).get(Source.SERVER).awaitTask().documents
        docs.chunked(400).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().awaitTask()
        }
    }
}
