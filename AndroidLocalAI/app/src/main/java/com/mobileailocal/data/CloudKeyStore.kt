package com.mobileailocal.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API キーを包む形式: [版 1B][IV 12B][暗号文 + GCM タグ]。AAD は uid + ":" + integrationId。
 * 鍵の取り出しを差し替えられるようにして、JVM の単体テストでも検証できる(本番は Android Keystore の鍵)。
 */
class KeyBlobCodec(private val key: () -> SecretKey) {
    companion object {
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        private val random = SecureRandom()

        fun aad(uid: String, integrationId: String): ByteArray = "$uid:$integrationId".toByteArray(Charsets.UTF_8)

        /** uid をファイル名に出さないためのハッシュ(SHA-256 の先頭 16 バイトの 16 進)と連携 ID でファイル名にする。 */
        fun fileName(uid: String, integrationId: String): String {
            val h = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
            return h.take(16).joinToString("") { "%02x".format(it) } + "_" + integrationId + ".bin"
        }
    }

    fun encrypt(uid: String, integrationId: String, plain: String): ByteArray {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(aad(uid, integrationId))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
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
        } catch (e: java.security.InvalidKeyException) {
            Decrypted.Corrupt // 鍵が無効になった(KeyPermanentlyInvalidatedException を含む)
        } catch (_: Exception) {
            Decrypted.Transient // Keystore の一時的な失敗など
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
class CloudKeyStore(context: Context) {
    private val dir = File(context.filesDir, "cloud-keys")
    private val codec = KeyBlobCodec(::keystoreKey)

    suspend fun put(uid: String, integrationId: String, apiKey: String) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = File(dir, KeyBlobCodec.fileName(uid, integrationId))
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(codec.encrypt(uid, integrationId, apiKey))
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
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

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return createKey(strongBox = Build.VERSION.SDK_INT >= 28)
    }

    private fun createKey(strongBox: Boolean): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            // ユーザー認証は要求しない(バックグラウンド生成で復号できなくなるため)
        if (strongBox && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
        return try {
            gen.init(b.build())
            gen.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            if (strongBox) createKey(strongBox = false) else throw e
        }
    }

    private companion object {
        const val ALIAS = "mal_cloud_key_v1"
    }
}
