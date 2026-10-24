package com.mobileailocal.data

import com.mobileailocal.domain.planSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * 端末内(Room)とアカウントストア(Firestore)の連携メタデータを合わせる。
 * キーは同期しない。ネットワークが無い・失敗したときは何もせず false を返す(端末内の連携はそのまま使える)。
 */
class CloudSync(
    private val repo: CloudIntegrationRepository,
    private val store: CloudAccountStore,
    private val keys: CloudKeyStore,
    private val timeoutMs: Long = 20_000,
) {
    private val lock = Mutex()

    /** 同期する。成功したら true。 */
    suspend fun sync(uid: String): Boolean = lock.withLock {
        try {
            withTimeout(timeoutMs) {
                val remote = store.load(uid)
                val plan = planSync(repo.allForSync(uid), remote)
                for (item in plan.upload) {
                    store.upsert(uid, item)
                    repo.markSynced(uid, item.id)
                }
                for (id in plan.deleteRemote) {
                    store.delete(uid, id)
                    repo.delete(uid, id)
                }
                for (item in plan.saveLocal) repo.saveSynced(uid, item)
                for (id in plan.deleteLocal) {
                    keys.delete(uid, id)
                    repo.delete(uid, id)
                }
            }
            true
        } catch (e: CancellationException) {
            // withTimeout の時間切れも、通信できなかった扱い(次回また試す)
            if (e is kotlinx.coroutines.TimeoutCancellationException) false else throw e
        } catch (_: Exception) {
            false // オフライン・権限・一時的な失敗。詳細はログにも出さない
        }
    }

    /** サーバー上のこのアカウントの連携メタデータをすべて消す(アカウント削除用)。失敗は例外のまま。 */
    suspend fun deleteRemoteAll(uid: String) = withTimeout(timeoutMs) { store.deleteAll(uid) }
}
