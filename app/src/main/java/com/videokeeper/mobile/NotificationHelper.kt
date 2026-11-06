package com.videokeeper.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

enum class LongOperationKind(val label: String, val resultId: Int) {
    IMPORT("掃描影片", 2410),
    CROP("黑邊分析", 2411),
    DUPLICATES("內容去重", 2412)
}

/** Central notification contract for operations that can continue after the UI is backgrounded. */
object NotificationHelper {
    const val PROCESSING_CHANNEL_ID = "videokeeper-processing-v2"
    const val RESULT_CHANNEL_ID = "videokeeper-results-v1"
    const val EXPORT_NOTIFICATION_ID = 2401
    const val SESSION_NOTIFICATION_ID = 2402
    private const val EXPORT_RESULT_NOTIFICATION_ID = 2403

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
        manager.createNotificationChannel(
            NotificationChannel(
                RESULT_CHANNEL_ID,
                "影片處理結果",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "影片管家完成或失敗通知"
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

    fun showResult(
        context: Context,
        kind: LongOperationKind,
        message: String,
        success: Boolean
    ) {
        createChannels(context)
        val notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("影片管家・${kind.label}${if (success) "完成" else "已停止"}")
            .setContentText(message)
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(mainActivityIntent(context))
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(kind.resultId, notification)
        }
    }

    fun showExportResult(context: Context, message: String, success: Boolean) {
        createChannels(context)
        val notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("影片管家・工作佇列${if (success) "完成" else "已停止"}")
            .setContentText(message)
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(mainActivityIntent(context))
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(EXPORT_RESULT_NOTIFICATION_ID, notification)
        }
    }

    private fun mainActivityIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
