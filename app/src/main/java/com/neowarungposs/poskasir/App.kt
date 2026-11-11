package com.neowarungposs.poskasir

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("general", "Pemberitahuan", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel("subscription", "Status langganan", NotificationManager.IMPORTANCE_HIGH)
        )
        FlavorInit.onAppCreate(this)
    }
}
