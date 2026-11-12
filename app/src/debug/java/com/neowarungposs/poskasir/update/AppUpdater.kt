package com.neowarungposs.poskasir.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.neowarungposs.poskasir.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Self-update for the sideloaded test/admin builds (debug build type only, D30).
 * Play Store release builds get a no-op version of this file: Play updates them.
 */
private const val LATEST = "https://api.github.com/repos/neowarungposs/poskasir/releases/latest"

private data class Update(val tag: String, val build: Int, val url: String, val size: Long)

private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

private suspend fun check(): Update? = withContext(Dispatchers.IO) {
    val req = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
    val body = http.newCall(req).execute().use { if (!it.isSuccessful) return@withContext null; it.body?.string() } ?: return@withContext null
    val o = Json.parseToJsonElement(body).jsonObject
    val name = o["name"]?.jsonPrimitive?.content ?: ""
    val build = Regex("build (\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: return@withContext null
    if (build <= BuildConfig.VERSION_CODE) return@withContext null
    val prefix = if (BuildConfig.FLAVOR == "admin") "KasirAdmin-" else "KasirWarung-"
    val asset = o["assets"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { (it["name"]?.jsonPrimitive?.content ?: "").startsWith(prefix) }
        ?: return@withContext null
    Update(
        tag = o["tag_name"]?.jsonPrimitive?.content ?: "",
        build = build,
        url = asset["browser_download_url"]!!.jsonPrimitive.content,
        size = (asset["size"]?.jsonPrimitive?.content ?: "0").toLong(),
    )
}

private suspend fun download(ctx: Context, u: Update, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
    val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    val out = File(dir, "update-${u.build}.apk")
    http.newCall(Request.Builder().url(u.url).build()).execute().use { resp ->
        if (!resp.isSuccessful) throw IllegalStateException("Unduh gagal (${resp.code})")
        val total = resp.body!!.contentLength().takeIf { it > 0 } ?: u.size
        resp.body!!.byteStream().use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
    }
    out
}

private fun install(ctx: Context, file: File) {
    if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return
    }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", file)
    ctx.startActivity(
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** Checks GitHub Releases once per launch and offers the newer APK (installs over the old one, data kept). */
@Composable
fun UpdatePrompt() {
    val ctx = LocalContext.current
    var update by remember { mutableStateOf<Update?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { update = runCatching { check() }.getOrNull() }
    val u = update ?: return
    if (dismissed) return

    AlertDialog(
        onDismissRequest = { if (progress == null) dismissed = true },
        title = { Text("Versi baru tersedia") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${u.tag} (build ${u.build}) siap dipasang. Anda sekarang di ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}). Data di HP tetap aman.")
                progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                if (file != null) Text("Jika diminta, izinkan \"Instal aplikasi tidak dikenal\" untuk aplikasi ini, lalu tekan Pasang lagi.")
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            Button(enabled = progress == null || file != null, onClick = {
                val f = file
                if (f != null) { install(ctx, f); return@Button }
                error = null; progress = 0f
                scope.launch {
                    try { file = download(ctx, u) { p -> progress = p }; install(ctx, file!!) }
                    catch (e: Exception) { error = "Gagal mengunduh. Periksa internet lalu coba lagi."; progress = null }
                }
            }) { Text(if (file != null) "Pasang" else "Perbarui") }
        },
        dismissButton = { if (progress == null) TextButton(onClick = { dismissed = true }) { Text("Nanti") } },
    )
}
