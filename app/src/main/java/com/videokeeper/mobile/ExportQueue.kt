@file:android.annotation.SuppressLint("UnsafeOptInUsageError")

package com.videokeeper.mobile

import android.net.Uri
import android.provider.DocumentsContract
import android.content.Context
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

    private data class Preparation(
        val info: VideoInfo,
        val crop: CropMath.EncoderCrop,
        val capability: HardwareCapability,
        val bitrate: Int,
        val encoderWidthAlignment: Int,
        val encoderHeightAlignment: Int
    )

    companion object {
        private const val MAX_CODEC_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 350L
    }

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
            val preparation = runCatching {
                val info = analyzer.readInfo(android.net.Uri.parse(item.uriString))
                val advertisedAlignment = HardwareCodecInspector.hardwareEncoderAlignment(HardwareCodecInspector.H264)
                val crop = CropMath.encoderCrop(
                    sourceWidth = info.width,
                    sourceHeight = info.height,
                    leading = item.left,
                    trailing = item.right,
                    widthAlignment = advertisedAlignment.first,
                    heightAlignment = advertisedAlignment.second
                ) ?: error("來源影片尺寸或裁切範圍無效，未建立輸出檔")
                val bitrate = quality.bitrate(info, info.width, info.height)
                val capability = HardwareCodecInspector.inspect(
                    context = context,
                    uri = android.net.Uri.parse(item.uriString),
                    outputWidth = crop.outputWidth,
                    outputHeight = crop.outputHeight,
                    frameRate = info.frameRate
                )
                if (capability.supported) {
                    Preparation(
                        info,
                        crop,
                        capability,
                        bitrate,
                        advertisedAlignment.first,
                        advertisedAlignment.second
                    )
                } else {
                    // Some Qualcomm drivers advertise a 2-pixel alignment but
                    // reject otherwise valid H.264 frame sizes at configure
                    // time. Try a conservative macroblock-aligned crop before
                    // reporting the device as unsupported.
                    val safeCrop = CropMath.encoderCrop(
                        sourceWidth = info.width,
                        sourceHeight = info.height,
                        leading = item.left,
                        trailing = item.right,
                        widthAlignment = maxOf(16, advertisedAlignment.first),
                        heightAlignment = advertisedAlignment.second
                    )
                    if (safeCrop != null &&
                        (safeCrop.outputWidth != crop.outputWidth || safeCrop.outputHeight != crop.outputHeight)
                    ) {
                        val safeCapability = HardwareCodecInspector.inspect(
                            context = context,
                            uri = android.net.Uri.parse(item.uriString),
                            outputWidth = safeCrop.outputWidth,
                            outputHeight = safeCrop.outputHeight,
                            frameRate = info.frameRate
                        )
                        if (safeCapability.supported) {
                            Preparation(
                                info,
                                safeCrop,
                                safeCapability,
                                bitrate,
                                advertisedAlignment.first,
                                advertisedAlignment.second
                            )
                        } else {
                            Preparation(
                                info,
                                crop,
                                capability,
                                bitrate,
                                advertisedAlignment.first,
                                advertisedAlignment.second
                            )
                        }
                    } else {
                        Preparation(
                            info,
                            crop,
                            capability,
                            bitrate,
                            advertisedAlignment.first,
                            advertisedAlignment.second
                        )
                    }
                }
            }
            handler.post {
                if (cancelled || finished) return@post
                preparation.fold(
                    onSuccess = { ready ->
                        if (!ready.capability.supported) failCurrent(ready.capability.reason)
                        else startTransformer(item, ready, attempt = 0)
                    },
                    onFailure = { error ->
                        failCurrent(error.message ?: "無法準備硬體轉檔")
                    }
                )
            }
        }
    }

    private fun startTransformer(item: QueueItem, preparation: Preparation, attempt: Int) {
        if (cancelled || finished) return
        val info = preparation.info
        val crop = preparation.crop
        val output = File(context.cacheDir, "videokeeper-${UUID.randomUUID()}.mp4")
        temporaryFile = output
        val edited = EditedMediaItem.Builder(
            MediaItem.fromUri(android.net.Uri.parse(item.uriString))
        ).setEffects(
            Effects(
                emptyList(),
                listOf(
                    Crop(
                        crop.left * 2f - 1f,
                        1f - crop.right * 2f,
                        -1f + crop.bottom * 2f,
                        1f - crop.top * 2f
                    )
                )
            )
        ).build()
        val decoderFactory = DefaultDecoderFactory.Builder(context)
            .setEnableDecoderFallback(false)
            .setMediaCodecSelector(HardwareCodecInspector.hardwareOnlyDecoderSelector())
            .build()
        val encoderSelector = EncoderSelector { mime ->
            if (mime.equals(MimeTypes.VIDEO_H264, ignoreCase = true)) {
                ImmutableList.copyOf(
                    HardwareCodecInspector.hardwareEncoderInfos(
                        mime = mime,
                        width = crop.outputWidth,
                        height = crop.outputHeight,
                        frameRate = info.frameRate
                    )
                )
            } else {
                // The video selector remains hardware-only. Audio uses Media3's
                // normal selector so software AAC is available when necessary.
                EncoderSelector.DEFAULT.selectEncoderInfos(mime)
            }
        }
        val encoderSettings = VideoEncoderSettings.Builder()
            .setBitrate(preparation.bitrate)
            // Qualcomm devices can reject Media3's default max-throughput
            // operating-rate hint even though the same hardware encoder works.
            // Omitting only this optional hint keeps the hardware path intact.
            .setEncoderPerformanceParameters(
                VideoEncoderSettings.RATE_UNSET,
                VideoEncoderSettings.RATE_UNSET
            )
            .build()
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setVideoEncoderSelector(encoderSelector)
            // Fallback is safe here: the selector never returns a software video
            // encoder. It lets Media3 clamp bitrate to the hardware range and
            // choose another hardware encoder when one is available.
            .setEnableFallback(true)
            .setEnableFormatFallback(false)
            .setRequestedVideoEncoderSettings(encoderSettings)
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
            if (info.hasAudio && !HardwareCodecInspector.canCopyAudio(info.audioMime)) {
                transformerBuilder.setAudioMimeType(MimeTypes.AUDIO_AAC)
            }
            transformer = transformerBuilder.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        transformer = null
                        if (cancelled || finished) {
                            output.delete()
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
                        handleTransformerFailure(
                            item = item,
                            preparation = preparation,
                            attempt = attempt,
                            output = output,
                            reason = readableError(exportException, preparation)
                        )
                    }
                })
                .build()
            transformer!!.start(edited, output.absolutePath)
            postProgress()
        } catch (error: Exception) {
            transformer = null
            handleTransformerFailure(
                item = item,
                preparation = preparation,
                attempt = attempt,
                output = output,
                reason = "無法啟動硬體轉檔：${compactMessage(error.message)}"
            )
        }
    }

    private fun handleTransformerFailure(
        item: QueueItem,
        preparation: Preparation,
        attempt: Int,
        output: File,
        reason: String
    ) {
        output.delete()
        if (finished || cancelled || current?.id != item.id) return
        if (attempt + 1 < MAX_CODEC_ATTEMPTS) {
            val retryPreparation = if (attempt == 0) {
                val safeCrop = CropMath.encoderCrop(
                    sourceWidth = preparation.info.width,
                    sourceHeight = preparation.info.height,
                    leading = item.left,
                    trailing = item.right,
                    widthAlignment = maxOf(16, preparation.encoderWidthAlignment),
                    heightAlignment = retryHeightAlignment(preparation)
                )
                if (safeCrop != null) preparation.copy(crop = safeCrop) else preparation
            } else {
                preparation
            }
            onUpdate(
                item.copy(
                    state = QueueState.RUNNING,
                    progress = 0,
                    error = if (retryPreparation.crop.outputWidth != preparation.crop.outputWidth ||
                        retryPreparation.crop.outputHeight != preparation.crop.outputHeight
                    ) {
                        "硬體編碼器拒絕目前尺寸，改用對齊尺寸重試…"
                    } else {
                        "硬體編碼器暫時拒絕目前設定，正在重試…"
                    },
                    updatedAt = now()
                )
            )
            handler.postDelayed({
                if (!finished && !cancelled && current?.id == item.id) {
                    startTransformer(item, retryPreparation, attempt + 1)
                }
            }, RETRY_DELAY_MS)
        } else {
            failCurrent(reason)
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
                val sourceUri = Uri.parse(item.uriString)
                val destinationUri = Uri.parse(item.destinationUriString)
                val sameFolder = sourceAndDestinationAreSameFolder(sourceUri, destinationUri)
                val filename = exportFilename(
                    originalName = item.name,
                    sameFolder = sameFolder,
                    uniqueToken = UUID.randomUUID().toString().take(8)
                )
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
                    if (cancelled || finished) {
                        temporary.delete()
                        return@post
                    }
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
                if (cancelled || finished) {
                    temporary.delete()
                    return@post
                }
                temporaryFile = null
                onUpdate(failed)
                index++
                current = null
                next()
            }
        }
    }

    private fun failCurrent(reason: String) {
        if (finished || cancelled) return
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

    private fun readableError(error: ExportException, preparation: Preparation): String {
        val category = when (error.errorCode) {
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED -> "硬體編碼器初始化失敗"
            ExportException.ERROR_CODE_ENCODING_FAILED -> "硬體影片編碼失敗"
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED -> "硬體編碼器不支援此輸出格式"
            ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED -> "硬體畫面處理失敗"
            else -> "硬體轉檔失敗"
        }
        val codec = error.codecInfo?.name?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""
        val info = preparation.info
        val audio = when {
            !info.hasAudio -> "無音訊"
            HardwareCodecInspector.canCopyAudio(info.audioMime) -> "AAC 音訊複製"
            else -> "音訊轉 AAC"
        }
        return "$category$codec；輸出 ${preparation.crop.outputWidth}×${preparation.crop.outputHeight}、${info.frameRate} fps、$audio；已重試仍失敗，未建立正式輸出檔"
    }

    /**
     * H.264 drivers often need a macroblock-aligned cropped width, but a
     * visible 1080-pixel height is commonly valid even though its stride is
     * padded internally. Do not remove visible rows just to satisfy the
     * conservative width retry.
     */
    private fun retryHeightAlignment(preparation: Preparation): Int =
        if (preparation.info.height >= 16 && preparation.info.height % 16 == 0) {
            maxOf(16, preparation.encoderHeightAlignment)
        } else {
            preparation.encoderHeightAlignment.coerceAtLeast(2)
        }

    private fun compactMessage(message: String?): String =
        message?.lineSequence()?.firstOrNull()?.take(220) ?: "未知錯誤"

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

enum class ExportQuality { STANDARD, HIGH, ORIGINAL;
    fun bitrate(info: VideoInfo?, width: Int, height: Int): Int {
        val source = info?.bitrate?.takeIf { it > 0 }
            ?: (width.toLong() * height.toLong() * 4L).coerceIn(1_000_000L, 24_000_000L).toInt()
        return when (this) {
            STANDARD -> (source * 0.78f).toInt().coerceAtLeast(1_000_000)
            HIGH -> source.coerceAtLeast(1_500_000)
            ORIGINAL -> source
        }
    }
}

/** Keeps the source name in a separate destination, while avoiding same-folder collisions. */
internal fun exportFilename(originalName: String, sameFolder: Boolean, uniqueToken: String): String {
    val safeName = originalName
        .ifBlank { "影片.mp4" }
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .take(120)
    if (!sameFolder) return safeName
    val baseName = safeName.substringBeforeLast('.', safeName)
    return "${baseName}_去黑邊_$uniqueToken.mp4"
}

/** Compares SAF document IDs instead of display names, which can be localized or duplicated. */
internal fun sourceAndDestinationAreSameFolder(source: Uri, destinationTree: Uri): Boolean = runCatching {
    if (source.authority != destinationTree.authority) return false
    val sourceDocumentId = DocumentsContract.getDocumentId(source)
    val destinationDocumentId = DocumentsContract.getTreeDocumentId(destinationTree)
    val separator = sourceDocumentId.lastIndexOf('/')
    val sourceParentId = if (separator >= 0) {
        sourceDocumentId.substring(0, separator)
    } else {
        sourceDocumentId.substringBeforeLast(':', sourceDocumentId) + ":"
    }
    sourceParentId == destinationDocumentId
}.getOrDefault(false)
