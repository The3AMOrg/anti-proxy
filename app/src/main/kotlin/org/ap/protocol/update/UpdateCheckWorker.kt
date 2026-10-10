package org.ap.protocol.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.ap.protocol.BuildConfig
import org.ap.protocol.R
import org.ap.protocol.ui.MainActivity
import java.util.concurrent.TimeUnit

/** Once a day, while online: if a newer release exists, notify (once per release). */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = UpdatePrefs(applicationContext)
        val settings = prefs.read()
        if (!settings.notify) return Result.success()
        return when (val r = GitHubReleases.check(BuildConfig.VERSION_NAME, settings.includePrerelease)) {
            is UpdateResult.Success -> {
                if (r.isNewer && prefs.notifiedTag != r.release.tagName) {
                    notify(r.release)
                    prefs.notifiedTag = r.release.tagName
                }
                Result.success()
            }
            UpdateResult.NetworkError, UpdateResult.Timeout -> Result.retry()
            else -> Result.success()
        }
    }

    private fun notify(release: Release) {
        val context = applicationContext
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a new version of AntiProxy is released"
            }
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_release_alert)
                .setContentTitle("AntiProxy ${release.tagName} is available")
                .setContentText("Open AntiProxy to see what's new and update.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    companion object {
        private const val WORK = "update-check"
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 3001

        /** Turns the daily check on or off. KEEP: re-scheduling on every app start doesn't reset the timer. */
        fun schedule(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled) {
                work.cancelUniqueWork(WORK)
                return
            }
            work.enqueueUniquePeriodicWork(
                WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
        }
    }
}
