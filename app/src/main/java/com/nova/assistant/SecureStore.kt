package com.nova.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API key is encrypted with AES-256-GCM. The encryption key lives inside the
 * Android Keystore (hardware backed on most phones) and cannot be read out.
 */
object SecureStore {
    private const val ALIAS = "nova_api_key_v1"
    private const val PREF = "nova_secure"
    private const val ENTRY = "api_key"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun saveKey(ctx: Context, key: String): Boolean = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = c.doFinal(key.trim().toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(ENTRY, blob).commit()
    } catch (e: Exception) {
        false
    }

    fun getKey(ctx: Context): String = try {
        val blob = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(ENTRY, null)
        if (blob.isNullOrEmpty()) "" else {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val ct = raw.copyOfRange(12, raw.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        ""
    }

    /** Multiple user-owned API keys for automatic failover. Stored as one encrypted JSON array. */
    fun saveKeys(ctx: Context, keys: List<String>): Boolean = try {
        val clean = keys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = c.doFinal(JSONArray(clean).toString().toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("api_keys", blob).commit()
    } catch (e: Exception) { false }

    fun getKeys(ctx: Context): List<String> = try {
        val blob = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("api_keys", null)
        if (blob.isNullOrEmpty()) emptyList() else {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val ct = raw.copyOfRange(12, raw.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            val a = JSONArray(String(c.doFinal(ct), Charsets.UTF_8))
            List(a.length()) { a.getString(it) }.filter { it.isNotBlank() }
        }
    } catch (e: Exception) { emptyList() }

    fun hasKeys(ctx: Context): Boolean = getKeys(ctx).isNotEmpty() || hasKey(ctx)


    /** Moves an old single encrypted key into the pool, then deletes the single-key copy. */
    fun migrateSingleToPool(ctx: Context) {
        val one = getKey(ctx)
        if (one.isEmpty()) return
        val pool = getKeys(ctx)
        val ok = if (pool.isEmpty()) saveKeys(ctx, listOf(one)) else true
        if (ok) clearKey(ctx)
    }

    /** Removes every stored key, including a legacy plain-text copy from very old versions. */
    fun clearKeys(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove("api_keys").remove(ENTRY).commit()
        ctx.getSharedPreferences("nova", Context.MODE_PRIVATE).edit().remove("key").commit()
    }

    fun hasKey(ctx: Context): Boolean = getKey(ctx).isNotEmpty()

    fun clearKey(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(ENTRY).apply()
    }

    /** Old versions kept the key as plain text. Encrypt it once and delete the plain copy. */
    fun migrateOld(ctx: Context) {
        val old = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)
        val plain = old.getString("key", "") ?: ""
        if (plain.isNotEmpty() && saveKey(ctx, plain)) {
            // commit() (not apply()) so the plain-text copy is really gone before we continue
            old.edit().remove("key").commit()
        }
    }
}
