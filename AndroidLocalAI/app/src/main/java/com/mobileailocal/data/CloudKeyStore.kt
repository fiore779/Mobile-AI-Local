package com.mobileailocal.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API キーを包む形式: [版 1B][IV 12B][暗号文 + GCM タグ]。AAD は uid + ":" + integrationId。
 * 鍵の取り出しを差し替えられるようにして、JVM の単体テストでも検証できる(本番は Android Keystore の鍵)。
 */
class KeyBlobCodec(
    /** 鍵が永久に無効になった(作り直すしかない)例外か。本番は KeyPermanentlyInvalidatedException だけ。 */
    private val isKeyInvalidated: (Throwable) -> Boolean = { it is android.security.keystore.KeyPermanentlyInvalidatedException },
    private val key: () -> SecretKey,
) {
    /** 鍵が永久に無効になったか(原因の連鎖も見る)。 */
    fun isInvalidated(e: Throwable): Boolean {
        var c: Throwable? = e
        var depth = 0
        while (c != null && depth < 4) {
            if (isKeyInvalidated(c)) return true
            c = c.cause
            depth++
        }
        return false
    }

    companion object {
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128

        fun aad(uid: String, integrationId: String): ByteArray = "$uid:$integrationId".toByteArray(Charsets.UTF_8)

        /** uid をファイル名に出さないためのハッシュ(SHA-256 の先頭 16 バイトの 16 進)と連携 ID でファイル名にする。 */
        fun fileName(uid: String, integrationId: String): String {
            val h = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
            return h.take(16).joinToString("") { "%02x".format(it) } + "_" + integrationId + ".bin"
        }
    }

    fun encrypt(uid: String, integrationId: String, plain: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        // IV は Cipher に作らせる。Android Keystore の鍵は既定で「呼び出し側が IV を渡す」ことを拒否する
        // (InvalidAlgorithmParameterException: Caller-provided IV not permitted)。
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(aad(uid, integrationId))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = c.iv
        check(iv != null && iv.size == IV_BYTES) { "unexpected IV length" }
        return byteArrayOf(VERSION) + iv + ct
    }

    /** 復号の結果。Corrupt は、そのファイルがもう使えない(消してよい)こと。Transient は一時的な失敗(消さない)。 */
    sealed interface Decrypted {
        data class Ok(val plain: String) : Decrypted
        data object Corrupt : Decrypted
        data object Transient : Decrypted
    }

    fun decryptDetailed(uid: String, integrationId: String, blob: ByteArray): Decrypted {
        if (blob.size < 1 + IV_BYTES + TAG_BITS / 8 || blob[0] != VERSION) return Decrypted.Corrupt
        return try {
            val iv = blob.copyOfRange(1, 1 + IV_BYTES)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            c.updateAAD(aad(uid, integrationId))
            Decrypted.Ok(String(c.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES), Charsets.UTF_8))
        } catch (e: javax.crypto.AEADBadTagException) {
            Decrypted.Corrupt // 改ざん・別の連携のファイル・鍵が変わった
        } catch (e: Exception) {
            // 鍵が永久に無効になったときだけ「もう使えない」(Corrupt)。汎用の InvalidKeyException や
            // Keystore の一時的な失敗は、ファイルを消さずに Transient で返す
            if (isInvalidated(e)) Decrypted.Corrupt else Decrypted.Transient
        }
    }

    /** 復号できなければ null(原因は問わない)。例外は外へ出さない。 */
    fun decrypt(uid: String, integrationId: String, blob: ByteArray): String? =
        (decryptDetailed(uid, integrationId, blob) as? Decrypted.Ok)?.plain
}

/**
 * API キーの暗号化保存。Android Keystore の AES-256-GCM 鍵(別名 mal_cloud_key_v1)で包み、
 * filesDir/cloud-keys/ に連携ごと 1 ファイルで置く。キー・暗号文・例外の詳細はログに出さない。
 */
class CloudKeyStore(
    context: Context,
    /** Keystore の鍵の別名。テストは別の別名を渡して、アプリ本体の鍵に触れないようにする。 */
    private val alias: String = DEFAULT_ALIAS,
    dirName: String = "cloud-keys",
) {
    private val dir = File(context.filesDir, dirName)
    private val codec = KeyBlobCodec(key = ::keystoreKey)
    // 鍵の取り出し・生成・作り直しを直列にする(同時に呼ばれて別名の鍵を二重に作らないため)
    private val keyLock = Any()

    suspend fun put(uid: String, integrationId: String, apiKey: String) = withContext(Dispatchers.IO) {
        val blob = try {
            codec.encrypt(uid, integrationId, apiKey)
        } catch (e: Exception) {
            // 鍵が永久に無効になっている(KeyPermanentlyInvalidatedException)ときだけ、鍵を作り直して 1 回だけやり直す。
            // それ以外の失敗(汎用の InvalidKeyException など)は作り直さず、そのまま失敗として返す(画面の詳細に出る)
            if (!codec.isInvalidated(e)) throw e
            resetKey()
            codec.encrypt(uid, integrationId, apiKey)
        }
        writeAtomically(File(dir, KeyBlobCodec.fileName(uid, integrationId)), blob)
    }

    /** 一時ファイルへ書いてから置き換える。置き換えに失敗したら例外(呼び出し側が画面に出す)。 */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw java.io.IOException("cannot create key directory")
        val tmp = File(dir, target.name + ".tmp")
        try {
            tmp.writeBytes(bytes)
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete() // 失敗したときの一時ファイルを残さない(成功時は移動済みで何も起きない)
        }
    }

    /** キーが無い、または復号できない(鍵が無効になった等)なら null。復号できないファイルは削除する。 */
    suspend fun get(uid: String, integrationId: String): String? = withContext(Dispatchers.IO) {
        val f = File(dir, KeyBlobCodec.fileName(uid, integrationId))
        if (!f.exists()) return@withContext null
        val r = try {
            codec.decryptDetailed(uid, integrationId, f.readBytes())
        } catch (_: Exception) {
            KeyBlobCodec.Decrypted.Transient // 読み取りの失敗は消さない
        }
        // 使えないと確定したファイルだけ消す。一時的な失敗では残して、次回また試す
        if (r == KeyBlobCodec.Decrypted.Corrupt) f.delete()
        (r as? KeyBlobCodec.Decrypted.Ok)?.plain
    }

    suspend fun has(uid: String, integrationId: String): Boolean = withContext(Dispatchers.IO) {
        File(dir, KeyBlobCodec.fileName(uid, integrationId)).exists()
    }

    suspend fun delete(uid: String, integrationId: String) = withContext(Dispatchers.IO) {
        File(dir, KeyBlobCodec.fileName(uid, integrationId)).delete()
        Unit
    }

    /** そのアカウントのキーをすべて消す(ファイル名の前半が uid のハッシュ)。 */
    suspend fun deleteAllFor(uid: String) = withContext(Dispatchers.IO) {
        val prefix = KeyBlobCodec.fileName(uid, "").removeSuffix(".bin")
        dir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
        Unit
    }

    private fun keystoreKey(): SecretKey = synchronized(keyLock) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = try {
            ks.getKey(alias, null) as? SecretKey
        } catch (e: java.security.UnrecoverableKeyException) {
            null // 鍵が壊れている: 作り直す(この鍵で包んだ既存のキーは復号できなくなり、入れ直しになる)
        }
        if (existing != null) return@synchronized existing
        deleteAlias(ks)
        createKey(strongBox = Build.VERSION.SDK_INT >= 28)
    }

    private fun resetKey() = synchronized(keyLock) {
        deleteAlias(KeyStore.getInstance("AndroidKeyStore").apply { load(null) })
    }

    private fun deleteAlias(ks: KeyStore) {
        try { if (ks.containsAlias(alias)) ks.deleteEntry(alias) } catch (_: Exception) {}
    }

    /**
     * 鍵を作る。StrongBox を試し、作れない・使えない場合は(例外の種類を問わず)通常の Keystore で作り直す。
     * 端末によっては StrongBox の鍵が作れても暗号化で失敗するので、作った直後に暗号化・復号を試してから採用する。
     */
    private fun createKey(strongBox: Boolean): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            // IV は Cipher に作らせる(setRandomizedEncryptionRequired は既定の true のまま)。
            // ユーザー認証は要求しない(バックグラウンド生成で復号できなくなるため)
        if (strongBox && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
        return try {
            gen.init(b.build())
            val k = gen.generateKey()
            probe(k)
            k
        } catch (e: Exception) {
            if (!strongBox) throw e
            // StrongBoxUnavailableException に限らず(ProviderException、KeyStoreException、
            // InvalidAlgorithmParameterException など端末ごとに違う)、通常の Keystore でやり直す
            deleteAlias(KeyStore.getInstance("AndroidKeyStore").apply { load(null) })
            createKey(strongBox = false)
        }
    }

    /** 作った鍵で、暗号化と復号が実際にできることを確かめる。 */
    private fun probe(k: SecretKey) {
        val enc = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
        val ct = enc.doFinal(byteArrayOf(1, 2, 3))
        val dec = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(KeyBlobCodec.TAG_BITS, enc.iv)) }
        check(dec.doFinal(ct).contentEquals(byteArrayOf(1, 2, 3))) { "key probe failed" }
    }

    companion object {
        /** 本番の鍵の別名。 */
        const val DEFAULT_ALIAS = "mal_cloud_key_v1"
    }
}
