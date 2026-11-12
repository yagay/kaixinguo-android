package com.kadaliao.kaixinguo.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encryption, AndroidKeyStore-backed key and noBackupFilesDir storage.
 * The application's normal session.json, favorites.json and history.json are
 * untouched and cannot accidentally contain website credentials.
 */
internal interface AccountPersistence {
    fun load(): StoredWebsiteSession?
    fun save(session: StoredWebsiteSession)
    fun clear()
}

internal class AccountStore(context: Context) : AccountPersistence {
    private val file = AtomicFile(File(context.noBackupFilesDir, "kxg_account_v1.enc"))
    private val alias = "com.kadaliao.kaixinguo.website_account.v1"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    override fun load(): StoredWebsiteSession? {
        if (!file.baseFile.exists()) return null
        val data = file.readFully()
        require(data.size > 32 && data[0] == 1.toByte()) { "Invalid account snapshot" }
        val iv = data.copyOfRange(1, 13)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        val json = JSONObject(String(cipher.doFinal(data.copyOfRange(13, data.size)), Charsets.UTF_8))
        val cookie = json.getString("cookie")
        require(cookie.length in 1..16384) { "Invalid account snapshot" }
        return StoredWebsiteSession(
            cookie,
            json.optString("nickname").take(120),
            json.optString("website_user_id").take(128),
            json.optLong("verified_at", 0L),
            source = if (json.optString("source") == "APP") SessionSource.APP else SessionSource.WEBSITE,
            deviceId = json.optString("device_id").take(30),
            installId = json.optString("install_id").take(30),
        )
    }

    override fun save(session: StoredWebsiteSession) {
        val payload = JSONObject()
            .put("cookie", session.cookie)
            .put("nickname", session.nickname)
            .put("website_user_id", session.websiteUserId)
            .put("verified_at", session.verifiedAtEpochMs)
            .put("source", session.source.name)
            .put("device_id", session.deviceId)
            .put("install_id", session.installId)
            .toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        require(cipher.iv.size == 12)
        val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(payload)
        val out = file.startWrite()
        try {
            out.write(encrypted)
            file.finishWrite(out)
        } catch (error: Exception) {
            file.failWrite(out)
            throw error
        }
    }

    override fun clear() {
        file.delete()
    }
}
