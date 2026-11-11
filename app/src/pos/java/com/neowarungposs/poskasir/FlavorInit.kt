package com.neowarungposs.poskasir

import android.content.Context
import com.neowarungposs.poskasir.pos.SyncScheduler

object FlavorInit {
    fun onAppCreate(app: App) {
        SyncScheduler.schedulePeriodic(app)
    }

    fun onPushData(context: Context, data: Map<String, String>) {
        if (data["type"] == "ENTITLEMENT_UPDATED") SyncScheduler.syncNow(context)
    }
}
