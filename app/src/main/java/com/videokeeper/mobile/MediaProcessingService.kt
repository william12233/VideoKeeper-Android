package com.videokeeper.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Android 15 mediaProcessing foreground service for durable, sequential exports. */
class MediaProcessingService : Service() {
    companion object {
        private const val CHANNEL_ID = "videokeeper-media-processing"
        private const val NOTIFICATION_ID = 2401
    }

    private var queue: ExportQueue? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == QueueStore.ACTION_CANCEL) {
            queue?.cancel()
            if (queue == null) stopService()
            return START_NOT_STICKY
        }
        try {
            startForegroundCompat("準備處理影片工作…", 0)
            if (queue == null) startQueue()
        } catch (error: Exception) {
            // A device may reject the declared FGS type or notification even
            // after the Activity accepted the start request. Convert that into
            // durable queue failures instead of crashing the application process.
            val reason = "Android 拒絕啟動本機處理服務：${error.message ?: "請檢查通知與背景處理權限"}"
            QueueStore.load(this)
                .filter { it.state == QueueState.QUEUED || it.state == QueueState.RUNNING }
                .forEach { item ->
                    QueueStore.update(this, item.copy(state = QueueState.FAILED, progress = 0, error = reason))
                }
            runCatching { stopService() }
        }
        return START_NOT_STICKY
    }

    private fun startQueue() {
        val stored = QueueStore.load(this)
        val pending = stored.filter { it.state == QueueState.QUEUED || it.state == QueueState.RUNNING }
            .map { if (it.state == QueueState.RUNNING) it.copy(state = QueueState.QUEUED, progress = 0) else it }
        if (pending.isEmpty()) {
            stopService()
            return
        }
        pending.forEach { QueueStore.update(this, it) }
        val quality = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("quality", ExportQuality.HIGH.name)
            ?.let { runCatching { ExportQuality.valueOf(it) }.getOrNull() }
            ?: ExportQuality.HIGH
        queue = ExportQueue(
            context = this,
            items = pending,
            quality = quality,
            onUpdate = { item ->
                QueueStore.update(this, item)
                val percent = item.progress.coerceIn(0, 100)
                startForegroundCompat("${item.name}　$percent%", percent)
            },
            onFinish = { cancelled ->
                queue = null
                startForegroundCompat(if (cancelled) "影片工作已取消" else "影片工作已完成", 100)
                stopService()
            }
        ).also { it.start() }
    }

    private fun startForegroundCompat(message: String, progress: Int) {
        val notification = buildNotification(message, progress)
        when {
            Build.VERSION.SDK_INT >= 35 -> {
                // AndroidX Core 1.16's ServiceCompat API-34 shim masks future
                // service-type bits. Calling the Android 15+ platform overload
                // directly is required or mediaProcessing becomes type none.
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                )
            }
            Build.VERSION.SDK_INT >= 29 -> {
                // mediaProcessing does not exist before Android 15. dataSync is
                // the valid compatibility type for the same user-initiated job.
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
            else -> {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildNotification(message: String, progress: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.videokeeper.mobile.R.drawable.ic_launcher_monochrome)
            .setContentTitle("影片管家・本機處理")
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(progress < 100)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "影片處理工作",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "影片管家背景裁切與完整性檢查進度" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun stopService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        queue?.cancel()
        queue = null
        super.onDestroy()
    }

    @android.annotation.SuppressLint("MissingSuperCall")
    override fun onTimeout(startId: Int, fgsType: Int) {
        queue?.cancel()
        queue = null
        stopService()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
