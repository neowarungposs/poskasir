package com.neowarungposs.poskasir.core

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.neowarungposs.poskasir.App
import com.neowarungposs.poskasir.FlavorInit
import com.neowarungposs.poskasir.MainActivity
import com.neowarungposs.poskasir.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PushService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { runCatching { FcmRegistrar.register((application as App).container.supabase, applicationContext, token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        FlavorInit.onPushData(applicationContext, data)
        val title = message.notification?.title ?: data["title"]
        val body = message.notification?.body ?: data["body"]
        if (title != null || body != null) {
            showNotification(applicationContext, data["channel"] ?: "general", title ?: "", body ?: "")
        }
    }
}

fun showNotification(context: Context, channel: String, title: String, body: String, id: Int = (System.currentTimeMillis() % 100000).toInt()) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return
    val intent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val n = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setContentIntent(intent)
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(context).notify(id, n)
}

object FcmRegistrar {
    /** Saves this device's FCM token for the signed-in user (upsert). */
    suspend fun register(client: SupabaseClient, context: Context, token: String? = null) {
        val session = client.session.state.value ?: return
        val fcm = token ?: runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() ?: return
        @Suppress("HardwareIds")
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val body = buildJsonObject {
            put("token", fcm)
            put("user_id", session.userId)
            put("device_id", deviceId)
            put("app_flavor", com.neowarungposs.poskasir.BuildConfig.FLAVOR)
        }
        client.post("fcm_tokens?on_conflict=token", "[$body]", prefer = "resolution=merge-duplicates,return=minimal")
    }

    suspend fun unregister(client: SupabaseClient) {
        val fcm = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() ?: return
        runCatching { client.delete("fcm_tokens?token=eq.$fcm") }
    }
}
