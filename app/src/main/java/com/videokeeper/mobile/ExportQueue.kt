package com.videokeeper.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Crop
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Exports sequentially; originals are read-only. Transformer needs a local output path. */
@OptIn(UnstableApi::class)
class ExportQueue(
    private val context: Context,
    private val destination: DocumentFile,
    private val items: List<VideoEntry>,
    private val onStatus: (String) -> Unit,
    private val onFinish: (Int, Int, Boolean) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var transformer: Transformer? = null
    private var temp: File? = null
    private var index = 0
    private var done = 0
    private var failed = 0
    private var cancelled = false
    private var finished = false

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        next()
    }

    fun cancel() {
        cancelled = true
        transformer?.cancel()
        transformer = null
        temp?.delete()
        finish()
    }

    private fun updateProgress() {
        if (cancelled || finished || transformer == null) return
        val holder = ProgressHolder()
        val state = transformer?.getProgress(holder)
        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
            onStatus("裁切轉檔 ${index + 1}/${items.size}：${holder.progress}%　完成 $done / 失敗 $failed")
        }
        handler.postDelayed({ updateProgress() }, 650L)
    }

    private fun next() {
        if (cancelled || finished) { finish(); return }
        if (index >= items.size) { finish(); return }
        val item = items[index]
        if (!item.crop.cropped || item.crop.left + item.crop.right >= 0.90f) {
            failed++; index++; next(); return
        }
        onStatus("開始裁切 ${index + 1}/${items.size}：${item.name}")
        val filename = item.name.substringBeforeLast('.', item.name).take(65)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_") + "_直立_" + UUID.randomUUID().toString().take(6) + ".mp4"
        val output = File(context.cacheDir, "cropping_" + UUID.randomUUID().toString() + ".mp4")
        temp = output
        val left = item.crop.left * 2f - 1f
        val right = 1f - item.crop.right * 2f
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(item.uri))
            .setEffects(Effects(emptyList(), listOf(Crop(left, right, -1f, 1f))))
            .build()
        try {
            transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        transformer = null
                        if (cancelled) { output.delete(); finish(); return }
                        onStatus("正在儲存：$filename")
                        io.execute {
                            var created: DocumentFile? = null
                            var ok = false
                            try {
                                created = destination.createFile("video/mp4", filename)
                                    ?: error("無法在輸出資料夾建立檔案")
                                context.contentResolver.openOutputStream(created.uri, "w")!!.use { target ->
                                    output.inputStream().buffered().use { input -> input.copyTo(target, 1024 * 1024) }
                                }
                                ok = true
                            } catch (_: Exception) {
                                try { created?.delete() } catch (_: Exception) {}
                            } finally {
                                output.delete()
                            }
                            handler.post {
                                if (ok) done++ else failed++
                                index++
                                next()
                            }
                        }
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        transformer = null
                        output.delete()
                        if (!cancelled) {
                            failed++
                            onStatus("轉檔失敗：${item.name}（${exportException.message ?: "編碼器不支援"}）")
                            index++
                            next()
                        } else finish()
                    }
                })
                .build()
            transformer!!.start(edited, output.absolutePath)
            updateProgress()
        } catch (e: Exception) {
            transformer = null
            output.delete()
            failed++
            onStatus("無法開始轉檔：${item.name}（${e.message}）")
            index++
            next()
        }
    }

    private fun finish() {
        if (finished) return
        finished = true
        temp?.delete()
        io.shutdown()
        onFinish(done, failed, cancelled)
    }
}
