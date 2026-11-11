package com.neowarungposs.poskasir.pos

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.neowarungposs.poskasir.core.formatDate
import com.neowarungposs.poskasir.core.showNotification
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = PosModule.repo(applicationContext)
        repo.container.session.load()
        return try {
            repo.loadEntitlement()
            repo.syncAll()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}

/** Local reminder from cached entitlement: works offline (Section 8). */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = PosModule.repo(applicationContext)
        repo.container.session.load()
        if (repo.container.session.state.value == null) return Result.success()
        repo.loadEntitlement()
        val s = repo.ent.value ?: return Result.success()
        val today = LocalDate.now().toString()
        if (repo.dao.kv("reminder_day") == today) return Result.success()

        val gate = repo.gate()
        val until = formatDate(Instant.ofEpochMilli(s.accessUntilMs))
        val text = when {
            gate.state == GateState.GRACE -> "Masa tenggang: langganan berakhir $until. Kasir masih bisa dipakai sebentar lagi."
            gate.state == GateState.EXPIRED -> "Langganan sudah berakhir ($until). Aplikasi dalam mode baca saja."
            gate.daysLeft in listOf(7L, 3L, 2L, 1L) -> "Langganan berakhir ${gate.daysLeft} hari lagi ($until)."
            gate.daysLeft == 0L -> "Langganan berakhir hari ini ($until)."
            else -> null
        }
        if (text != null) {
            showNotification(applicationContext, "subscription", "Status langganan", "$text Kode pelanggan: ${s.customerCode}", id = 4242)
            repo.dao.putKv(KvEntity("reminder_day", today))
        }
        return Result.success()
    }
}

object SyncScheduler {
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            "sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
        )
        wm.enqueueUniquePeriodicWork(
            "reminder", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(12, TimeUnit.HOURS).build()
        )
    }

    fun syncNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "sync-now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online).build()
        )
    }
}
