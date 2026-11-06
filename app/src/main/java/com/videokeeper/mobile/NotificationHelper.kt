package com.videokeeper.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

enum class LongOperationKind(val label: String) {
    IMPORT("掃描影片"),
    CROP("黑邊分析"),
    DUPLICATES("內容去重")
}

/** Converts item-based queue progress into one 0..100 progress bar. */
internal fun aggregateQueueProgress(
    totalItems: Int,
    completedItems: Int,
    currentItemProgress: Int
): Int {
    val total = totalItems.coerceAtLeast(1)
    val completed = completedItems.coerceIn(0, total)
    val current = currentItemProgress.coerceIn(0, 100)
    return ((completed * 100 + current) / total).coerceIn(0, 100)
}

/** Central notification contract for operations that can continue after the UI is backgrounded. */
object NotificationHelper {
    const val PROCESSING_CHANNEL_ID = "videokeeper-processing-v2"
    const val EXPORT_NOTIFICATION_ID = 2401
    const val SESSION_NOTIFICATION_ID = 2402

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                PROCESSING_CHANNEL_ID,
                "影片處理進度",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "影片管家分析、去重與硬體轉檔進度"
            }
        )
    }

    fun buildProcessingNotification(
        context: Context,
        title: String,
        message: String,
        progress: Int
    ): Notification {
        createChannels(context)
        return NotificationCompat.Builder(context, PROCESSING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .setContentIntent(mainActivityIntent(context))
            .build()
    }

    fun showProgress(context: Context, kind: LongOperationKind, message: String, progress: Int) {
        createChannels(context)
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(
                SESSION_NOTIFICATION_ID,
                buildProcessingNotification(
                    context = context,
                    title = "影片管家・${kind.label}",
                    message = message,
                    progress = progress
                )
            )
        }
    }

    fun cancelProgress(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).apply {
                cancel(SESSION_NOTIFICATION_ID)
                cancel(EXPORT_NOTIFICATION_ID)
            }
        }
    }

    private fun mainActivityIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
