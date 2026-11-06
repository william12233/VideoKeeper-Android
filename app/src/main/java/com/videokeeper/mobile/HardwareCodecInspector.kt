@file:android.annotation.SuppressLint("UnsafeOptInUsageError")

package com.videokeeper.mobile

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.net.Uri
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * Hardware-only gate for the export path. Transformer is never started until this gate passes.
 */
object HardwareCodecInspector {
    const val H264 = "video/avc"
    const val HEVC = "video/hevc"
    const val AAC = "audio/mp4a-latm"

    fun inspect(context: Context, uri: Uri, requireAudio: Boolean): HardwareCapability {
        val tracks = readTracks(context, uri)
        val sourceMime = tracks.firstOrNull { it.startsWith("video/") }
        if (sourceMime == null) {
            return HardwareCapability(null, null, null, null, false, "找不到可解碼的影片軌")
        }
        val decoder = decoderInfos(sourceMime).firstOrNull()
        if (decoder == null) {
            return HardwareCapability(
                sourceMime, null, null, null, false,
                "裝置沒有 ${sourceMime} 的硬體解碼器，已拒絕慢速軟體路徑"
            )
        }
        val encoder = codecInfos(H264, true).firstOrNull()
        if (encoder == null) {
            return HardwareCapability(
                sourceMime, decoder.name, null, null, false,
                "裝置沒有 H.264 硬體編碼器，無法使用高速輸出"
            )
        }
        val audioMime = tracks.firstOrNull { it.startsWith("audio/") }
        val audioEncoder = if (requireAudio && audioMime != null) codecInfos(AAC, true).firstOrNull() else null
        if (requireAudio && audioMime != null && audioEncoder == null) {
            return HardwareCapability(
                sourceMime, decoder.name, encoder.name, null, false,
                "來源含音訊，但裝置沒有 AAC 硬體編碼器；為避免無聲輸出已停止"
            )
        }
        return HardwareCapability(
            sourceMime = sourceMime,
            decoderName = decoder.name,
            encoderName = encoder.name,
            audioEncoderName = audioEncoder?.name,
            supported = true,
            reason = if (audioMime == null) {
                "硬體解碼／H.264 編碼可用；來源沒有音訊軌"
            } else {
                "硬體解碼／H.264／AAC 路徑可用"
            }
        )
    }

    fun hardwareEncoderNames(mime: String): List<String> = codecInfos(mime, true).map { it.name }

    fun hardwareEncoderInfos(mime: String): List<MediaCodecInfo> = codecInfos(mime, true)

    fun hardwareDecoderNames(mime: String): List<String> = decoderInfos(mime).map { it.name }

    fun hardwareOnlyDecoderSelector(): MediaCodecSelector = MediaCodecSelector { mime, secure, tunneling ->
        MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            .filter { info -> info.hardwareAccelerated && !info.softwareOnly }
    }

    fun isHardwareCodecName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val lower = name.lowercase()
        if (lower.contains("google") || lower.contains("software") || lower.contains("swcodec") ||
            lower.startsWith("c2.android") || lower.startsWith("omx.google")) return false
        return true
    }

    private fun decoderInfos(mime: String): List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> =
        runCatching {
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, false, false)
                .filter { it.hardwareAccelerated && !it.softwareOnly }
        }.getOrDefault(emptyList())

    private fun codecInfos(mime: String, encoder: Boolean): List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder == encoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                isHardware(info)
        }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isSoftwareOnly
        else isHardwareCodecName(info.name)

    private fun readTracks(context: Context, uri: Uri): List<String> {
        val extractor = MediaExtractor()
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                extractor.setDataSource(descriptor.fileDescriptor)
                (0 until extractor.trackCount).mapNotNull { index ->
                    extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)
                }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        } finally {
            extractor.release()
        }
    }
}
