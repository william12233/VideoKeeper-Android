@file:android.annotation.SuppressLint("UnsafeOptInUsageError")

package com.videokeeper.mobile

import android.content.Context
import android.media.MediaCodecInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.effect.Crop
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Serial Media3 Transformer queue. It refuses to start when hardware preflight fails. */
class ExportQueue(
    private val context: Context,
    private val items: List<QueueItem>,
    private val quality: ExportQuality,
    private val onUpdate: (QueueItem) -> Unit,
    private val onFinish: (cancelled: Boolean) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val analyzer by lazy { Analyzer(context) }
    private var transformer: Transformer? = null
    private var temporaryFile: File? = null
    private var index = 0
    @Volatile private var cancelled = false
    private var finished = false
    private var current: QueueItem? = null
    private var startedAt = 0L

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        next()
    }

    fun cancel() {
        if (finished) return
        cancelled = true
        transformer?.cancel()
        transformer = null
        temporaryFile?.delete()
        current?.let { item ->
            onUpdate(item.copy(state = QueueState.CANCELLED, error = "使用者取消工作", updatedAt = now()))
        }
        items.drop(index + 1).forEach { item ->
            onUpdate(item.copy(state = QueueState.CANCELLED, error = "工作佇列已取消", updatedAt = now()))
        }
        finish()
    }

    private fun next() {
        if (cancelled || index >= items.size) {
            finish()
            return
        }
        val item = items[index]
        current = item
        startedAt = SystemClock.elapsedRealtime()
        onUpdate(item.copy(state = QueueState.RUNNING, progress = 0, error = null, updatedAt = now()))
        io.execute {
            val info = runCatching { analyzer.readInfo(android.net.Uri.parse(item.uriString)) }.getOrNull()
            val capability = runCatching {
                HardwareCodecInspector.inspect(
                    context,
                    android.net.Uri.parse(item.uriString)
                )
            }.getOrElse {
                HardwareCapability(null, null, null, null, false, "硬體能力檢查失敗：${it.message}")
            }
            handler.post {
                if (cancelled) return@post
                if (!capability.supported) {
                    failCurrent(capability.reason)
                } else {
                    startTransformer(item, info, capability)
                }
            }
        }
    }

    private fun startTransformer(item: QueueItem, info: VideoInfo?, capability: HardwareCapability) {
        val output = File(context.cacheDir, "videokeeper-${UUID.randomUUID()}.mp4")
        temporaryFile = output
        val cropLeft = item.left.coerceIn(0f, 0.44f)
        val cropRight = item.right.coerceIn(0f, 0.44f)
        if (cropLeft + cropRight >= 0.88f) {
            failCurrent("裁切範圍無效，保留原始檔未輸出")
            return
        }
        val edited = EditedMediaItem.Builder(
            MediaItem.fromUri(android.net.Uri.parse(item.uriString))
        ).setEffects(
            Effects(
                emptyList(),
                listOf(Crop(cropLeft * 2f - 1f, 1f - cropRight * 2f, -1f, 1f))
            )
        ).build()
        val bitrate = quality.bitrate(info, item.sourceWidth, item.sourceHeight)
        val decoderFactory = DefaultDecoderFactory.Builder(context)
            .setEnableDecoderFallback(false)
            .setMediaCodecSelector(HardwareCodecInspector.hardwareOnlyDecoderSelector())
            .build()
        val encoderSelector = EncoderSelector { mime ->
            ImmutableList.copyOf(HardwareCodecInspector.hardwareEncoderInfos(mime))
        }
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setVideoEncoderSelector(encoderSelector)
            .setEnableFallback(false)
            .setEnableFormatFallback(false)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(bitrate).build()
            )
            .build()
        try {
            val transformerBuilder = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setPortraitEncodingEnabled(true)
                .setAssetLoaderFactory(DefaultAssetLoaderFactory(context, decoderFactory, Clock.DEFAULT, null))
                .setEncoderFactory(encoderFactory)
            // Leaving the audio MIME type unset lets Transformer transmux a
            // compatible AAC track. For other audio codecs, request AAC and
            // allow Media3's normal audio encoder selection, including software
            // AAC when the device has no hardware AAC encoder.
            if (info?.hasAudio == true && !HardwareCodecInspector.canCopyAudio(info.audioMime)) {
                transformerBuilder.setAudioMimeType(MimeTypes.AUDIO_AAC)
            }
            transformer = transformerBuilder.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        transformer = null
                        if (cancelled) {
                            output.delete()
                            finish()
                            return
                        }
                        if (!HardwareCodecInspector.isHardwareCodecName(exportResult.videoEncoderName)) {
                            output.delete()
                            failCurrent("實際輸出未使用硬體 H.264 編碼器，已拒絕檔案")
                            return
                        }
                        copyAndValidate(item, info, output, exportResult)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        transformer = null
                        output.delete()
                        failCurrent(readableError(exportException))
                    }
                })
                .build()
            transformer!!.start(edited, output.absolutePath)
            postProgress()
        } catch (error: Exception) {
            transformer = null
            output.delete()
            failCurrent("無法啟動硬體轉檔：${error.message ?: "未知錯誤"}")
        }
    }

    private fun copyAndValidate(
        item: QueueItem,
        info: VideoInfo?,
        temporary: File,
        exportResult: ExportResult
    ) {
        onUpdate(item.copy(state = QueueState.RUNNING, progress = 99, updatedAt = now()))
        io.execute {
            if (cancelled) {
                temporary.delete()
                return@execute
            }
            val destination = DocumentFile.fromTreeUri(context, android.net.Uri.parse(item.destinationUriString))
            var created: DocumentFile? = null
            var message: String? = null
            var outputUri: String? = null
            var elapsed = SystemClock.elapsedRealtime() - startedAt
            try {
                if (destination == null || !destination.isDirectory) error("輸出資料夾已失效")
                val safeName = item.name.substringBeforeLast('.', item.name)
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .take(70)
                val filename = "${safeName}_去黑邊_${UUID.randomUUID().toString().take(8)}.mp4"
                created = destination.createFile("video/mp4", filename)
                    ?: error("無法建立輸出檔案，請檢查資料夾權限與剩餘空間")
                context.contentResolver.openOutputStream(created!!.uri, "w")!!.use { target ->
                    temporary.inputStream().buffered().use { source -> source.copyTo(target, 1024 * 1024) }
                }
                val validation = OutputValidator.validate(
                    context,
                    created!!.uri,
                    expectedAudio = info?.hasAudio == true,
                    expectedDurationMs = item.sourceDurationMs
                )
                if (!validation.valid) error(validation.reason)
                if (exportResult.videoEncoderName.isNullOrBlank()) error("缺少實際編碼器資訊")
                if (cancelled) {
                    created?.delete()
                    return@execute
                }
                outputUri = created!!.uri.toString()
                elapsed = SystemClock.elapsedRealtime() - startedAt
                val speed = if (elapsed > 0) item.sourceDurationMs.toFloat() / elapsed else 0f
                val done = item.copy(
                    state = QueueState.SUCCEEDED,
                    progress = 100,
                    elapsedMs = elapsed,
                    speed = speed,
                    outputUriString = outputUri,
                    updatedAt = now()
                )
                handler.post {
                    temporary.delete()
                    temporaryFile = null
                    onUpdate(done)
                    index++
                    current = null
                    next()
                }
                return@execute
            } catch (error: Exception) {
                message = error.message ?: "輸出失敗"
                created?.delete()
            } finally {
                temporary.delete()
            }
            val failed = item.copy(
                state = QueueState.FAILED,
                progress = 0,
                elapsedMs = elapsed,
                error = message,
                updatedAt = now()
            )
            handler.post {
                temporaryFile = null
                onUpdate(failed)
                index++
                current = null
                next()
            }
        }
    }

    private fun failCurrent(reason: String) {
        val item = current ?: return
        temporaryFile?.delete()
        temporaryFile = null
        onUpdate(item.copy(state = QueueState.FAILED, progress = 0, error = reason, updatedAt = now()))
        index++
        current = null
        next()
    }

    private fun postProgress() {
        if (finished || cancelled || transformer == null || current == null) return
        val holder = ProgressHolder()
        if (transformer!!.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
            onUpdate(current!!.copy(state = QueueState.RUNNING, progress = holder.progress, updatedAt = now()))
        }
        handler.postDelayed({ postProgress() }, 600L)
    }

    private fun readableError(error: ExportException): String =
        "硬體轉檔失敗：${error.message ?: error.cause?.message ?: "裝置不支援此影片格式"}"

    private fun finish() {
        if (finished) return
        finished = true
        transformer?.cancel()
        transformer = null
        temporaryFile?.delete()
        temporaryFile = null
        io.shutdown()
        onFinish(cancelled)
    }

    private fun now(): Long = System.currentTimeMillis()
}

enum class ExportQuality { STANDARD, HIGH, MAXIMUM;
    fun bitrate(info: VideoInfo?, width: Int, height: Int): Int {
        val source = info?.bitrate?.takeIf { it > 0 }
            ?: (width.toLong() * height.toLong() * 4L).coerceIn(1_000_000L, 24_000_000L).toInt()
        return when (this) {
            STANDARD -> (source * 0.78f).toInt().coerceAtLeast(1_000_000)
            HIGH -> source.coerceAtLeast(1_500_000)
            MAXIMUM -> (source * 1.15f).toInt().coerceAtMost(50_000_000)
        }
    }
}
