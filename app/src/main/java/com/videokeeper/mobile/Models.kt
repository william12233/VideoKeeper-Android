package com.videokeeper.mobile

import android.net.Uri

enum class CropKind { AUTO, REVIEW, NONE, UNSUPPORTED }

enum class AnalysisState { PENDING, RUNNING, READY, NEEDS_REVIEW, FAILED, UNSUPPORTED }

data class CropDecision(
    val left: Float = 0f,
    val right: Float = 0f,
    val kind: CropKind = CropKind.NONE,
    val note: String = "尚未分析",
    val confidence: Int = 0,
    val validSamples: Int = 0,
    val sampleCount: Int = 0,
    val selected: Boolean = false
) {
    val cropped: Boolean get() = left > 0.015f || right > 0.015f
    val totalCrop: Float get() = left + right

    fun withSelection(checked: Boolean): CropDecision = copy(selected = checked)
}

data class VideoInfo(
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0,
    val videoMime: String? = null,
    val audioMime: String? = null,
    val bitrate: Int = 0,
    val frameRate: Float = 0f,
    val hasAudio: Boolean = false,
    val videoFrameCount: Long = 0L
) {
    val displayWidth: Int get() = if (rotationDegrees % 180 == 0) width else height
    val displayHeight: Int get() = if (rotationDegrees % 180 == 0) height else width
    val isPortrait: Boolean get() = displayHeight > displayWidth && displayWidth > 0
}

data class VideoEntry(
    val id: String,
    val uri: Uri,
    val name: String,
    val relativePath: String,
    val bytes: Long,
    val modified: Long,
    val info: VideoInfo? = null,
    val crop: CropDecision = CropDecision(),
    val suggestedCrop: CropDecision? = null,
    val fingerprint: List<FrameSignature> = emptyList(),
    val cropState: AnalysisState = AnalysisState.PENDING,
    val fingerprintState: AnalysisState = AnalysisState.PENDING,
    val selected: Boolean = true,
    val error: String? = null
) {
    val durationMs: Long get() = info?.durationMs ?: 0L
    val width: Int get() = info?.width ?: 0
    val height: Int get() = info?.height ?: 0
    val outputWidth: Int
        get() = if (width > 0) {
            val raw = (width * (1f - crop.left - crop.right)).toInt().coerceAtLeast(2)
            raw - raw % 2
        } else 0
    val outputHeight: Int get() = height - height % 2
}

data class FrameSignature(
    val phash: Long,
    val dhash: Long,
    val sampleIndex: Int = 0,
    val luma: Int = 0,
    val color: Int = 0,
    val usable: Boolean = true
)

data class DuplicatePair(
    val first: VideoEntry,
    val second: VideoEntry,
    val score: Int,
    val verifiedScore: Int,
    val offsetSamples: Int,
    val highConfidence: Boolean,
    val note: String
)

data class QueueItem(
    val id: String,
    val uriString: String,
    val name: String,
    val destinationUriString: String,
    val left: Float,
    val right: Float,
    val sourceDurationMs: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val state: QueueState = QueueState.QUEUED,
    val progress: Int = 0,
    val elapsedMs: Long = 0L,
    val speed: Float = 0f,
    val outputUriString: String? = null,
    val error: String? = null,
    val sourceDeleted: Boolean = false,
    val sourceDeleteError: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

enum class QueueState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class SimilarityResult(
    val score: Int,
    val matchedFrames: Int,
    val offsetSamples: Int,
    val medianDistance: Int
)

data class HardwareCapability(
    val sourceMime: String?,
    val decoderName: String?,
    val encoderName: String?,
    val audioEncoderName: String?,
    val supported: Boolean,
    val reason: String
)

data class VerificationResult(
    val score: Int,
    val matchedFrames: Int,
    val offsetSamples: Int
)
