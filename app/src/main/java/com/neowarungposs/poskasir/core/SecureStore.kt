package com.neowarungposs.poskasir.core

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.secureDataStore by preferencesDataStore(name = "secure_store")

/**
 * DataStore whose values are encrypted with Tink AES-256-GCM.
 * The Tink keyset is itself wrapped by a key in the Android Keystore.
 * (EncryptedSharedPreferences is deprecated and not used.)
 */
class SecureStore(private val context: Context) {

    @Volatile
    private var aeadInstance: Aead? = null

    @Suppress("DEPRECATION")
    private fun aead(): Aead {
        aeadInstance?.let { return it }
        synchronized(this) {
            aeadInstance?.let { return it }
            AeadConfig.register()
            val handle = AndroidKeysetManager.Builder()
                .withSharedPref(context, "tink_keyset", "tink_prefs")
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri("android-keystore://poskasir_master_key")
                .build()
                .keysetHandle
            val created = handle.getPrimitive(Aead::class.java)
            aeadInstance = created
            return created
        }
    }

    suspend fun put(key: String, value: String?) = withContext(Dispatchers.IO) {
        val prefKey = stringPreferencesKey(key)
        val encrypted = value?.let {
            Base64.encodeToString(aead().encrypt(it.toByteArray(), key.toByteArray()), Base64.NO_WRAP)
        }
        context.secureDataStore.edit { prefs ->
            if (encrypted == null) prefs.remove(prefKey) else prefs[prefKey] = encrypted
        }
    }

    suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        val raw = context.secureDataStore.data.first()[stringPreferencesKey(key)] ?: return@withContext null
        runCatching {
            String(aead().decrypt(Base64.decode(raw, Base64.NO_WRAP), key.toByteArray()))
        }.getOrNull()
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        context.secureDataStore.edit { it.clear() }
    }
}
