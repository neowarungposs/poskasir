package com.neowarungposs.poskasir.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Tenant logo and login background cached on the device (D21), so the login / lock screen
 * can show the warung's own branding after the first login, also offline.
 */
class BrandingStore(private val context: Context) {
    private val dir = File(context.filesDir, "branding")
    val logoFile get() = File(dir, "logo.img")
    val backgroundFile get() = File(dir, "login_bg.img")
    private val prefs = context.getSharedPreferences("branding", Context.MODE_PRIVATE)

    /** Bumped whenever a file changes, so images recompose. */
    private val _version = MutableStateFlow(prefs.getLong("version", 0))
    val version: StateFlow<Long> = _version

    fun logo(): File? = logoFile.takeIf { it.exists() }
    fun background(): File? = backgroundFile.takeIf { it.exists() }

    suspend fun sync(client: SupabaseClient, logoPath: String?, bgPath: String?) {
        var changed = false
        changed = syncOne(client, logoPath, logoFile, "logo_path") || changed
        changed = syncOne(client, bgPath, backgroundFile, "bg_path") || changed
        if (changed) bump()
    }

    private suspend fun syncOne(client: SupabaseClient, path: String?, file: File, key: String): Boolean {
        val current = prefs.getString(key, null)
        if (path == current && (path == null || file.exists())) return false
        if (path == null) {
            file.delete(); prefs.edit().remove(key).apply(); return true
        }
        val url = client.signedUrl("outlet-assets", path) ?: return false
        val bytes = withContext(Dispatchers.IO) {
            client.http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
        } ?: return false
        withContext(Dispatchers.IO) { dir.mkdirs(); file.writeBytes(bytes) }
        prefs.edit().putString(key, path).apply()
        return true
    }

    fun clear() {
        logoFile.delete(); backgroundFile.delete()
        prefs.edit().clear().apply()
        bump()
    }

    private fun bump() {
        val v = System.currentTimeMillis()
        prefs.edit().putLong("version", v).apply()
        _version.value = v
    }
}
