package com.neowarungposs.poskasir.core

import android.util.Base64
import com.neowarungposs.poskasir.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalSerializationApi::class)
val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}

/** HTTP error from Supabase. [code] is the server error code (e.g. FORBIDDEN, PRICE_COLLISION). */
class ApiException(val status: Int, val code: String?, message: String) : Exception(message)

@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val userId: String,
    val email: String? = null,
)

data class Claims(val appRole: String?, val tenantId: String?, val outletId: String?)

@Serializable
private data class AuthUser(val id: String, val email: String? = null)

@Serializable
private data class AuthResponse(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresIn: Long? = null,
    val expiresAt: Long? = null,
    val user: AuthUser? = null,
    val id: String? = null,
)

class SessionManager internal constructor(private val store: SecureStore) {
    private val _state = MutableStateFlow<Session?>(null)
    val state: StateFlow<Session?> = _state
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded
    private val mutex = Mutex()
    internal var refresher: (suspend (String) -> Session)? = null

    suspend fun load() {
        if (_loaded.value) return
        _state.value = store.get(KEY)?.let { runCatching { AppJson.decodeFromString<Session>(it) }.getOrNull() }
        _loaded.value = true
    }

    suspend fun save(session: Session) {
        store.put(KEY, AppJson.encodeToString(Session.serializer(), session))
        _state.value = session
    }

    suspend fun clear() {
        store.put(KEY, null)
        _state.value = null
    }

    /** Returns a usable access token, refreshing when close to expiry. Null when signed out. */
    suspend fun validAccessToken(force: Boolean = false): String? = mutex.withLock {
        val current = _state.value ?: return@withLock null
        val now = System.currentTimeMillis() / 1000
        if (!force && current.expiresAt - 60 > now) return@withLock current.accessToken
        try {
            val refreshed = refresher!!.invoke(current.refreshToken)
            save(refreshed)
            refreshed.accessToken
        } catch (e: ApiException) {
            if (e.status in 400..499) {
                clear(); null
            } else current.accessToken
        } catch (e: IOException) {
            current.accessToken // offline: keep working with cached token
        }
    }

    fun claims(): Claims? = _state.value?.let { decodeClaims(it.accessToken) }

    companion object {
        private const val KEY = "session"

        fun decodeClaims(jwt: String): Claims? = runCatching {
            val payload = jwt.split(".")[1]
            val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val obj = Json.parseToJsonElement(json).jsonObject
            fun str(k: String) = obj[k]?.jsonPrimitive?.contentOrNull
            Claims(str("app_role"), str("tenant_id"), str("outlet_id"))
        }.getOrNull()
    }
}

class SupabaseClient(store: SecureStore) {
    val session = SessionManager(store)
    private val base = BuildConfig.SUPABASE_URL
    private val key = BuildConfig.SUPABASE_KEY
    private val jsonType = "application/json".toMediaType()
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        session.refresher = { refresh -> authRequest("token?grant_type=refresh_token", buildJsonObject { put("refresh_token", refresh) }) }
    }

    // ------------------------------------------------------------------ auth
    suspend fun signIn(email: String, password: String): Session {
        val s = authRequest("token?grant_type=password", buildJsonObject {
            put("email", email.trim()); put("password", password)
        })
        session.save(s)
        return s
    }

    /** Returns true when a session was created; false when email confirmation is required. */
    suspend fun signUp(email: String, password: String): Boolean {
        val body = buildJsonObject { put("email", email.trim()); put("password", password) }
        val raw = send("POST", "$base/auth/v1/signup", body.toString(), auth = false)
        val res = AppJson.decodeFromString<AuthResponse>(raw)
        if (res.accessToken == null) return false
        session.save(res.toSession())
        return true
    }

    suspend fun refreshSession(): Boolean = session.validAccessToken(force = true) != null

    suspend fun signOut() {
        runCatching { send("POST", "$base/auth/v1/logout", "{}", auth = true) }
        session.clear()
    }

    private suspend fun authRequest(path: String, body: JsonObject): Session {
        val raw = send("POST", "$base/auth/v1/$path", body.toString(), auth = false)
        return AppJson.decodeFromString<AuthResponse>(raw).toSession()
    }

    private fun AuthResponse.toSession(): Session {
        val token = accessToken ?: throw ApiException(400, null, "Sesi tidak valid")
        val exp = expiresAt ?: (System.currentTimeMillis() / 1000 + (expiresIn ?: 3600))
        return Session(token, refreshToken ?: "", exp, user?.id ?: "", user?.email)
    }

    // ------------------------------------------------------------------ data
    suspend fun get(pathAndQuery: String): String = send("GET", "$base/rest/v1/$pathAndQuery", null)

    suspend fun post(path: String, body: String, prefer: String = "return=representation"): String =
        send("POST", "$base/rest/v1/$path", body, headers = mapOf("Prefer" to prefer))

    suspend fun patch(pathAndQuery: String, body: String): String =
        send("PATCH", "$base/rest/v1/$pathAndQuery", body, headers = mapOf("Prefer" to "return=representation"))

    suspend fun delete(pathAndQuery: String): String = send("DELETE", "$base/rest/v1/$pathAndQuery", null)

    suspend fun rpc(fn: String, args: JsonObject = JsonObject(emptyMap())): String =
        send("POST", "$base/rest/v1/rpc/$fn", args.toString())

    suspend fun function(name: String, body: JsonObject = JsonObject(emptyMap())): String =
        send("POST", "$base/functions/v1/$name", body.toString())

    suspend fun signedUrl(bucket: String, path: String, expiresIn: Int = 3600): String? = runCatching {
        val raw = send(
            "POST", "$base/storage/v1/object/sign/$bucket/$path",
            buildJsonObject { put("expiresIn", expiresIn) }.toString()
        )
        val rel = AppJson.parseToJsonElement(raw).jsonObject["signedURL"]?.jsonPrimitive?.contentOrNull
        rel?.let { if (it.startsWith("http")) it else "$base/storage/v1$it" }
    }.getOrNull()

    /** Uploads (or replaces) a file in Storage. Path must start with the tenant id folder. */
    suspend fun upload(bucket: String, path: String, bytes: ByteArray, contentType: String) = withContext(Dispatchers.IO) {
        val token = session.validAccessToken() ?: throw ApiException(401, null, "Belum masuk")
        val req = Request.Builder()
            .url("$base/storage/v1/object/$bucket/$path")
            .header("apikey", key)
            .header("Authorization", "Bearer $token")
            .header("x-upsert", "true")
            .post(bytes.toRequestBody(contentType.toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw parseError(res.code, res.body?.string().orEmpty())
        }
    }

    // ------------------------------------------------------------------ transport
    private suspend fun send(
        method: String,
        url: String,
        body: String?,
        auth: Boolean = true,
        headers: Map<String, String> = emptyMap(),
        retried: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val token = if (auth) session.validAccessToken() else null
        val builder = Request.Builder().url(url)
            .header("apikey", key)
            .header("Accept", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        headers.forEach { (k, v) -> builder.header(k, v) }
        val reqBody = body?.toRequestBody(jsonType)
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete(reqBody)
            else -> builder.method(method, reqBody ?: "".toRequestBody(jsonType))
        }
        http.newCall(builder.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (res.code == 401 && auth && !retried && token != null) {
                if (session.validAccessToken(force = true) != null) {
                    return@withContext send(method, url, body, auth, headers, retried = true)
                }
            }
            if (!res.isSuccessful) throw parseError(res.code, text)
            text
        }
    }

    private fun parseError(status: Int, text: String): ApiException {
        val obj = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull()
        fun s(k: String) = obj?.get(k)?.let { el -> runCatching { el.jsonPrimitive.contentOrNull }.getOrNull() }
        val message = s("message") ?: s("msg") ?: s("error_description") ?: s("error") ?: "HTTP $status"
        val code = when {
            message.matches(Regex("^[A-Z_]+$")) -> message
            else -> s("error_code") ?: s("code")
        }
        return ApiException(status, code, message)
    }
}

/** Indonesian user-facing message for any error. */
fun friendlyError(e: Throwable): String = when (e) {
    is IOException -> "Tidak ada koneksi internet. Coba lagi."
    is ApiException -> when (e.code ?: e.message) {
        "invalid_credentials", "Invalid login credentials" -> "Email atau password salah."
        "email_not_confirmed", "Email not confirmed" -> "Email belum dikonfirmasi. Cek kotak masuk email Anda."
        "user_already_exists", "User already registered" -> "Email sudah terdaftar. Silakan masuk."
        "weak_password" -> "Password terlalu lemah (minimal 6 karakter)."
        "FORBIDDEN" -> "Anda tidak punya akses untuk aksi ini."
        "ALREADY_REGISTERED" -> "Akun ini sudah punya usaha terdaftar."
        "PRICE_COLLISION" -> "Harga bentrok dengan paket lain, ubah diskon sedikit."
        "INCOMING_ALREADY_USED" -> "Uang masuk ini sudah dipakai untuk aktivasi lain."
        "AMOUNT_TOO_LOW" -> "Nominal kurang dari harga paket."
        "CLAIM_MISMATCH" -> "Klaim tidak cocok dengan tenant yang dipilih."
        "CLAIM_NOT_PENDING" -> "Klaim ini sudah diproses."
        "DUPLICATE_PROOF" -> "Bukti bayar ini sudah pernah dikirim."
        "DUPLICATE_BANK_REF" -> "No. referensi ini sudah dicatat."
        "TENANT_NOT_LIVE" -> "Tenant sudah diarsip."
        "PLAN_OR_MONTHS_REQUIRED" -> "Pilih paket atau isi jumlah bulan."
        else -> e.message ?: "Terjadi kesalahan."
    }
    else -> e.message ?: "Terjadi kesalahan."
}

fun JsonElement.str(key: String): String? =
    runCatching { jsonObject[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
