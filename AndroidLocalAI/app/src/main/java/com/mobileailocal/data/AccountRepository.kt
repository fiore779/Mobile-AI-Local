package com.mobileailocal.data

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.mobileailocal.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope

data class GoogleAccount(val uid: String, val email: String?, val displayName: String?)

/** サインインの失敗。画面は種別から文言を決める(原因の詳細は出さない)。 */
sealed class AccountError : Exception() {
    /** 利用者が取り消した(エラーとして表示しない)。 */
    data object Cancelled : AccountError()
    /** 端末に Google アカウントが無い、または Google Play 開発者サービスが使えない。 */
    data object NoCredential : AccountError()
    data object Network : AccountError()
    /** アカウント削除に、直近のサインインが必要。 */
    data object RecentLoginRequired : AccountError()
    data object Other : AccountError()
}

/** firebase に依存しない uid の置き場(サインアウト中の仮の値。実在の uid と衝突しない)。 */
const val SIGNED_OUT_UID = ""

/**
 * Google サインイン(Credential Manager + Google ID → Firebase Auth)。
 * クラウド連携を使うときだけ使う。ローカルモデルだけの利用ではサインインを求めない。
 */
class AccountRepository(private val context: Context, scope: CoroutineScope) {
    private val auth = FirebaseAuth.getInstance()
    private val credentials = CredentialManager.create(context)

    private val _current = MutableStateFlow(auth.currentUser?.toAccount())

    /** 現在のアカウント(サインアウト中は null)。 */
    val current: StateFlow<GoogleAccount?> = _current

    /** 現在の uid(サインアウト中は null)。 */
    val uid: StateFlow<String?> = _current.map { it?.uid }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, _current.value?.uid)

    init {
        auth.addAuthStateListener { _current.value = it.currentUser?.toAccount() }
    }

    private fun FirebaseUser.toAccount() = GoogleAccount(uid, email, displayName)

    /** Google でサインインする。Activity が要る。失敗は AccountError。 */
    suspend fun signIn(activity: Activity): GoogleAccount {
        val option = GetSignInWithGoogleOption.Builder(context.getString(R.string.default_web_client_id)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = try {
            credentials.getCredential(activity, request)
        } catch (e: GetCredentialCancellationException) {
            throw AccountError.Cancelled
        } catch (e: NoCredentialException) {
            throw AccountError.NoCredential
        } catch (e: GetCredentialException) {
            throw AccountError.Other
        }
        val cred = result.credential
        if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw AccountError.Other
        }
        val idToken = try {
            GoogleIdTokenCredential.createFrom(cred.data).idToken
        } catch (e: GoogleIdTokenParsingException) {
            throw AccountError.Other
        }
        try {
            auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).awaitTask()
        } catch (e: FirebaseNetworkException) {
            throw AccountError.Network
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AccountError.Other
        }
        return auth.currentUser?.toAccount() ?: throw AccountError.Other
    }

    /** サインアウトする(端末内の連携とキーは残す。同じアカウントで入り直すと使える)。 */
    suspend fun signOut() {
        auth.signOut()
        try {
            credentials.clearCredentialState(ClearCredentialStateRequest())
        } catch (_: Exception) {
            // 資格情報の消去に失敗しても、サインアウト自体は済んでいる
        }
    }

    /** Firebase のアカウント自体を削除する。サーバー上の連携メタデータは先に消しておくこと。 */
    suspend fun deleteAccount() {
        val user = auth.currentUser ?: return
        try {
            user.delete().awaitTask()
        } catch (e: FirebaseAuthRecentLoginRequiredException) {
            throw AccountError.RecentLoginRequired
        } catch (e: FirebaseNetworkException) {
            throw AccountError.Network
        }
        signOut()
    }
}
