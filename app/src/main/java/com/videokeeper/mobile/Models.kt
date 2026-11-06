package com.videokeeper.mobile

import android.net.Uri

enum class CropKind { AUTO, REVIEW, NONE }

data class CropDecision(
    var left: Float = 0f,
    var right: Float = 0f,
    var kind: CropKind = CropKind.NONE,
    var note: String = "尚未分析",
    var selected: Boolean = false
) {
    val cropped: Boolean get() = left > 0.015f || right > 0.015f
}

data class VideoEntry(
    val uri: Uri,
    val name: String,
    val relativePath: String,
    val bytes: Long,
    val modified: Long,
    var durationMs: Long = 0L,
    var width: Int = 0,
    var height: Int = 0,
    var crop: CropDecision = CropDecision(),
    var fingerprint: List<FrameSignature> = emptyList(),
    var error: String? = null
)

data class FrameSignature(val phash: Long, val dhash: Long, val sampleIndex: Int = 0)

data class DuplicatePair(
    val first: VideoEntry,
    val second: VideoEntry,
    val score: Int,
    val highConfidence: Boolean
)
