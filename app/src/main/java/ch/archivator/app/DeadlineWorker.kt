package ch.archivator.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class DeadlineWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val docs = ArchiveDb(applicationContext).use { it.all() }
        val today = LocalDate.now()
        val upcoming = docs.filter { d ->
            if (d.due.isBlank() || d.status in listOf("Bezahlt", "Erledigt")) return@filter false
            val due = runCatching { LocalDate.parse(d.due) }.getOrNull() ?: return@filter false
            !due.isAfter(today.plusDays(3))
        }
        if (upcoming.isEmpty()) return Result.success()
        val context = applicationContext
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("deadlines", "Archivator Fristen", NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()
        val intent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(234, NotificationCompat.Builder(context, "deadlines")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Archivator: ${upcoming.size} Frist(en)")
            .setContentText(upcoming.take(2).joinToString(", ") { it.title })
            .setContentIntent(intent)
            .setAutoCancel(true).build())
        return Result.success()
    }
}

fun scheduleDeadlines(context: Context) {
    val req = PeriodicWorkRequestBuilder<DeadlineWorker>(24, TimeUnit.HOURS).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "archive-deadlines", ExistingPeriodicWorkPolicy.KEEP, req
    )
}
