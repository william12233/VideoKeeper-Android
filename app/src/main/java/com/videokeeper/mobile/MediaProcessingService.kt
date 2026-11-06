package com.videokeeper.mobile

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

/** Android 15 mediaProcessing foreground service for exports and long media analysis sessions. */
class MediaProcessingService : Service() {
    private var queue: ExportQueue? = null
    private var processingSessionActive = false

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            QueueStore.ACTION_CANCEL -> {
                queue?.cancel()
                if (queue == null) stopService()
                return START_NOT_STICKY
            }
            QueueStore.ACTION_START_SESSION -> {
                startProcessingSession(intent.getStringExtra(QueueStore.EXTRA_SESSION_TITLE))
                return START_NOT_STICKY
            }
            QueueStore.ACTION_STOP_SESSION -> {
                if (queue == null) stopService()
                return START_NOT_STICKY
            }
        }
        try {
            startForegroundCompat("準備處理影片工作…", 0, NotificationHelper.EXPORT_NOTIFICATION_ID)
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
            NotificationHelper.showExportResult(this, reason, success = false)
            runCatching { stopService() }
        }
        return START_NOT_STICKY
    }

    private fun startProcessingSession(title: String?) {
        if (queue != null || processingSessionActive) return
        try {
            processingSessionActive = true
            startForegroundCompat(
                message = "準備${title ?: "本機分析"}…",
                progress = 0,
                notificationId = NotificationHelper.SESSION_NOTIFICATION_ID,
                title = "影片管家・本機處理"
            )
        } catch (_: Exception) {
            processingSessionActive = false
            runCatching { stopService() }
        }
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
                startForegroundCompat(
                    message = "${item.name}　$percent%",
                    progress = percent,
                    notificationId = NotificationHelper.EXPORT_NOTIFICATION_ID
                )
            },
            onFinish = { cancelled ->
                queue = null
                val result = QueueStore.load(this)
                val succeeded = result.count { it.state == QueueState.SUCCEEDED }
                val failed = result.count { it.state == QueueState.FAILED }
                val message = if (cancelled) {
                    "已取消；成功 $succeeded 支、失敗 $failed 支"
                } else {
                    "成功 $succeeded 支、失敗 $failed 支；失敗項目未留下正式輸出"
                }
                NotificationHelper.showExportResult(this, message, success = !cancelled && failed == 0)
                stopService()
            }
        ).also { it.start() }
    }

    private fun startForegroundCompat(
        message: String,
        progress: Int,
        notificationId: Int,
        title: String = "影片管家・本機處理"
    ) {
        val notification = NotificationHelper.buildProcessingNotification(this, title, message, progress)
        when {
            Build.VERSION.SDK_INT >= 35 -> {
                // AndroidX Core 1.16's ServiceCompat API-34 shim masks future
                // service-type bits. Calling the Android 15+ platform overload
                // directly is required or mediaProcessing becomes type none.
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                )
            }
            Build.VERSION.SDK_INT >= 29 -> {
                // mediaProcessing does not exist before Android 15. dataSync is
                // the valid compatibility type for the same user-initiated job.
                ServiceCompat.startForeground(
                    this,
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
            else -> {
                startForeground(notificationId, notification)
            }
        }
    }

    private fun stopService() {
        processingSessionActive = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        queue?.cancel()
        queue = null
        processingSessionActive = false
        super.onDestroy()
    }

    @android.annotation.SuppressLint("MissingSuperCall")
    override fun onTimeout(startId: Int, fgsType: Int) {
        queue?.cancel()
        queue = null
        if (processingSessionActive) {
            NotificationHelper.showExportResult(this, "Android 已逾時停止背景處理，請重新開始分析。", success = false)
        }
        processingSessionActive = false
        stopService()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
