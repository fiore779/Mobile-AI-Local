package com.mobileailocal.domain

/** ローカル(Room)の連携の同期状態。 */
enum class SyncState(val code: String) {
    /** まだ送っていない(新規・変更あり)。 */
    Local("local"),
    Synced("synced"),
    /** 削除待ち(他の端末・サーバーへ削除を伝えるまで残す)。 */
    Deleted("deleted");

    companion object {
        fun of(code: String): SyncState = entries.firstOrNull { it.code == code } ?: Local
    }
}

data class LocalIntegration(val item: CloudIntegration, val state: SyncState)

/** 同期でやること。呼び出し側が実行し、成功したものだけ同期済みにする。 */
data class SyncPlan(
    /** サーバーへ書く(成功したら同期済み)。 */
    val upload: List<CloudIntegration>,
    /** サーバーから削除する(成功したらローカルの削除待ちの行も消す)。 */
    val deleteRemote: List<String>,
    /** ローカルへ取り込む・更新する(同期済みとして保存)。 */
    val saveLocal: List<CloudIntegration>,
    /** ローカルの行を消す(他の端末で削除された、または削除待ちで相手側にも無い)。キーも消す。 */
    val deleteLocal: List<String>,
)

/** 同期の計画を立てる(純粋関数)。競合は updatedAt の新しいほうを採用する(後勝ち)。 */
fun planSync(local: List<LocalIntegration>, remote: List<CloudIntegration>): SyncPlan {
    val upload = mutableListOf<CloudIntegration>()
    val deleteRemote = mutableListOf<String>()
    val saveLocal = mutableListOf<CloudIntegration>()
    val deleteLocal = mutableListOf<String>()
    val localById = local.associateBy { it.item.id }
    val remoteById = remote.associateBy { it.id }

    for ((id, l) in localById) {
        val r = remoteById[id]
        when (l.state) {
            SyncState.Deleted -> if (r != null) deleteRemote += id else deleteLocal += id
            SyncState.Local -> when {
                // 端末内だけに置くもの(http・クエリつき URL など)は送らない
                !CloudInputs.syncable(l.item.baseUrl) -> {}
                r == null -> upload += l.item
                r.updatedAt > l.item.updatedAt -> saveLocal += r
                else -> upload += l.item
            }
            SyncState.Synced -> when {
                // 同期済みの行がサーバーに無い = 他の端末で削除された
                r == null -> deleteLocal += id
                r.updatedAt > l.item.updatedAt -> saveLocal += r
                r.updatedAt < l.item.updatedAt && CloudInputs.syncable(l.item.baseUrl) -> upload += l.item
            }
        }
    }
    // サーバーだけにあるもの(別の端末で作られた)は取り込む
    for ((id, r) in remoteById) if (id !in localById) saveLocal += r
    return SyncPlan(upload, deleteRemote, saveLocal, deleteLocal)
}
