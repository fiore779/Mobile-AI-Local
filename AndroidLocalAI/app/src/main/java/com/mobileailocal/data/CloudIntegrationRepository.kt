package com.mobileailocal.data

import com.mobileailocal.domain.CloudCredentials
import com.mobileailocal.domain.CloudIntegration
import com.mobileailocal.domain.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** フェーズ 1 の仮 uid(サインインなし)。実際のアカウントに切り替えるまで固定値を使う。 */
const val LOCAL_UID = "local"

/** 連携情報の読み書き(Room のローカル写し)。アカウントストアとの同期は後のタスクで足す。 */
class CloudIntegrationRepository(private val dao: CloudIntegrationDao) {
    suspend fun list(uid: String): List<CloudIntegration> = withContext(Dispatchers.IO) {
        dao.list(uid).mapNotNull { it.toDomain() }
    }

    suspend fun get(uid: String, id: String): CloudIntegration? = withContext(Dispatchers.IO) {
        dao.byId(uid, id)?.takeIf { it.syncState != "deleted" }?.toDomain()
    }

    fun observe(uid: String): kotlinx.coroutines.flow.Flow<List<CloudIntegration>> =
        dao.integrations(uid).map { rows -> rows.mapNotNull { it.toDomain() } }

    suspend fun upsert(uid: String, item: CloudIntegration) = withContext(Dispatchers.IO) {
        dao.upsert(item.toEntity(uid))
    }

    suspend fun delete(uid: String, id: String) = withContext(Dispatchers.IO) { dao.delete(uid, id) }

    suspend fun deleteAll(uid: String) = withContext(Dispatchers.IO) { dao.deleteAll(uid) }
}

internal fun CloudIntegration.toEntity(uid: String, syncState: String = "local") = CloudIntegrationEntity(
    uid = uid, id = id, type = type.name, displayName = displayName, baseUrl = baseUrl.trimEnd('/'),
    enabledModelsJson = JSONArray(enabledModels).toString(), lastModel = lastModel,
    createdAt = createdAt, updatedAt = updatedAt, syncState = syncState,
)

/** 種別が不明(将来の版で増えた値など)の行は null にして、一覧から外す。 */
internal fun CloudIntegrationEntity.toDomain(): CloudIntegration? {
    val t = ProviderType.entries.firstOrNull { it.name == type } ?: return null
    val models = try {
        JSONArray(enabledModelsJson).let { a -> (0 until a.length()).map { a.getString(it) } }
    } catch (_: Exception) {
        emptyList()
    }
    return CloudIntegration(id, t, displayName, baseUrl, models, lastModel, createdAt, updatedAt)
}

/** RemoteApiEngine 用: 現在のアカウントの連携と、端末内のキーを渡す。 */
class RepositoryCredentials(
    private val repo: CloudIntegrationRepository,
    private val keys: CloudKeyStore,
    private val uid: () -> String = { LOCAL_UID },
) : CloudCredentials {
    override suspend fun integration(id: String) = repo.get(uid(), id)
    override suspend fun apiKey(integrationId: String) = keys.get(uid(), integrationId)
}
