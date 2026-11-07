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
 * Hardware gate for the video export path. Audio is intentionally not part of
 * the hardware-only gate: compatible AAC is transmuxed, and incompatible audio
 * may use Media3's AAC software encoder when the user has allowed that fallback.
 */
object HardwareCodecInspector {
    const val H264 = "video/avc"
    const val HEVC = "video/hevc"
    const val AAC = "audio/mp4a-latm"

    fun inspect(
        context: Context,
        uri: Uri,
        outputWidth: Int = 0,
        outputHeight: Int = 0,
        frameRate: Float = 0f
    ): HardwareCapability {
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
        val allEncoders = codecInfos(H264, true)
        val encoder = hardwareEncoderInfos(H264, outputWidth, outputHeight, frameRate).firstOrNull()
        if (encoder == null) {
            return HardwareCapability(
                sourceMime, decoder.name, null, null, false,
                when {
                    allEncoders.isEmpty() -> "裝置沒有 H.264 硬體編碼器，無法使用高速輸出"
                    outputWidth > 0 && outputHeight > 0 ->
                        "硬體 H.264 編碼器不接受 ${outputWidth}×${outputHeight}（${frameRate.coerceAtLeast(0f)} fps）"
                    else -> "裝置的 H.264 硬體編碼器無法使用，無法使用高速輸出"
                }
            )
        }
        val audioMime = tracks.firstOrNull { it.startsWith("audio/") }
        val audioEncoder = if (audioMime != null) codecInfos(AAC, true).firstOrNull() else null
        return HardwareCapability(
            sourceMime = sourceMime,
            decoderName = decoder.name,
            encoderName = encoder.name,
            audioEncoderName = audioEncoder?.name,
            supported = true,
            reason = if (audioMime == null) {
                "硬體解碼／H.264 編碼可用；來源沒有音訊軌"
            } else if (canCopyAudio(audioMime)) {
                "硬體影片路徑可用；AAC 音訊將優先直接複製"
            } else {
                "硬體影片路徑可用；非 AAC 音訊必要時以 AAC 軟體編碼"
            }
        )
    }

    fun canCopyAudio(audioMime: String?): Boolean =
        audioMime?.equals(AAC, ignoreCase = true) == true

    fun hardwareEncoderNames(mime: String): List<String> = codecInfos(mime, true).map { it.name }

    fun hardwareEncoderInfos(mime: String): List<MediaCodecInfo> = codecInfos(mime, true)

    /** Returns the first hardware encoder's advertised pixel alignment. */
    fun hardwareEncoderAlignment(mime: String): Pair<Int, Int> =
        codecInfos(mime, true).asSequence().mapNotNull { info ->
            runCatching {
                info.getCapabilitiesForType(mime).videoCapabilities?.let { capabilities ->
                    capabilities.widthAlignment.coerceAtLeast(2) to
                        capabilities.heightAlignment.coerceAtLeast(2)
                }
            }.getOrNull()
        }.firstOrNull() ?: (2 to 2)

    fun hardwareEncoderInfos(
        mime: String,
        width: Int,
        height: Int,
        frameRate: Float
    ): List<MediaCodecInfo> {
        if (width <= 0 || height <= 0 || !mime.startsWith("video/")) {
            return hardwareEncoderInfos(mime)
        }
        return codecInfos(mime, true).filter { info ->
            runCatching {
                val capabilities = info.getCapabilitiesForType(mime).videoCapabilities
                    ?: return@runCatching true
                val rate = frameRate.takeIf { it > 0f }?.coerceAtMost(240f) ?: 30f
                capabilities.areSizeAndRateSupported(width, height, rate.toDouble()) ||
                    capabilities.areSizeAndRateSupported(height, width, rate.toDouble())
            }.getOrDefault(false)
        }
    }

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
