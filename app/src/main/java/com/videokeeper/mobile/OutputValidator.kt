package com.videokeeper.mobile

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor

data class OutputValidation(
    val valid: Boolean,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val durationMs: Long,
    val reason: String
)

object OutputValidator {
    fun validate(
        context: Context,
        uri: Uri,
        expectedAudio: Boolean,
        expectedDurationMs: Long
    ): OutputValidation {
        var hasVideo = false
        var hasAudio = false
        var durationUs = 0L
        return try {
            val extractor = MediaExtractor()
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                extractor.setDataSource(descriptor.fileDescriptor)
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/")) {
                        hasVideo = true
                        if (format.containsKey(MediaFormat.KEY_DURATION)) {
                            durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
                        }
                    }
                    if (mime.startsWith("audio/")) hasAudio = true
                }
            }
            extractor.release()
            val durationMs = durationUs / 1_000L
            val durationOkay = durationMs > 0 && (expectedDurationMs <= 0L ||
                durationMs >= (expectedDurationMs * 0.80).toLong())
            val valid = hasVideo && durationOkay && (!expectedAudio || hasAudio)
            OutputValidation(
                valid = valid,
                hasVideo = hasVideo,
                hasAudio = hasAudio,
                durationMs = durationMs,
                reason = when {
                    !hasVideo -> "輸出沒有影片軌"
                    expectedAudio && !hasAudio -> "來源有音訊，但輸出沒有音訊軌"
                    !durationOkay -> "輸出片長異常，疑似檔案不完整"
                    else -> "影片與音訊軌完整"
                }
            )
        } catch (error: Exception) {
            OutputValidation(false, hasVideo, hasAudio, 0L, "完整性檢查失敗：${error.message ?: "無法讀取輸出"}")
        }
    }
}
