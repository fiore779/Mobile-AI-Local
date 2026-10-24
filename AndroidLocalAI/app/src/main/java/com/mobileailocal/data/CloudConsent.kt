package com.mobileailocal.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mobileailocal.domain.ProviderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.cloudDataStore by preferencesDataStore("cloud")

/** 本文を変えたら上げて、再同意を求める。 */
const val CONSENT_VERSION = 1

/** クラウドへ送ることへの同意の記録(アカウント × プロバイダー種別)。 */
class CloudConsent(private val context: Context) {
    // scope: カスタムは接続先のホスト(接続先が変わったら再度同意を求める)。それ以外は空
    private fun key(uid: String, type: ProviderType, scope: String) =
        intPreferencesKey("cloud_consent_${uid}_${type.name}" + if (scope.isEmpty()) "" else "_$scope")

    /** 同意済みの版。0 は未同意。 */
    suspend fun acceptedVersion(uid: String, type: ProviderType, scope: String = ""): Int =
        context.cloudDataStore.data.map { it[key(uid, type, scope)] ?: 0 }.first()

    suspend fun isAccepted(uid: String, type: ProviderType, scope: String = ""): Boolean =
        acceptedVersion(uid, type, scope) >= CONSENT_VERSION

    suspend fun accept(uid: String, type: ProviderType, scope: String = "", version: Int = CONSENT_VERSION) {
        context.cloudDataStore.edit { it[key(uid, type, scope)] = version }
    }

    suspend fun revoke(uid: String, type: ProviderType, scope: String = "") {
        context.cloudDataStore.edit { it.remove(key(uid, type, scope)) }
    }
}
