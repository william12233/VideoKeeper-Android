package com.videokeeper.mobile

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A frame-level observation produced by the Android bitmap sampler. */
data class BorderObservation(
    val left: Float,
    val right: Float,
    val centreLuma: Int,
    val edgeLuma: Int,
    val usable: Boolean,
    val fullBlack: Boolean = false,
    val colouredEdge: Boolean = false
)

/**
 * Decides whether a crop is safe from several independent time samples.
 * This class contains no Android APIs so its safety thresholds can be unit tested.
 */
object BlackBarDetector {
    fun decide(
        width: Int,
        height: Int,
        rotationDegrees: Int,
        observations: List<BorderObservation>
    ): CropDecision {
        if (width <= 0 || height <= 0) {
            return CropDecision(kind = CropKind.UNSUPPORTED, note = "缺少有效影片尺寸")
        }
        if (rotationDegrees % 180 != 0) {
            return CropDecision(
                kind = CropKind.REVIEW,
                note = "影片含旋轉 metadata，請人工確認裁切方向",
                sampleCount = observations.size
            )
        }
        if (height >= width) {
            return CropDecision(
                kind = CropKind.NONE,
                note = "影片顯示方向已是直式，不自動裁切",
                sampleCount = observations.size
            )
        }
        val useful = observations.filter { it.usable && !it.fullBlack && !it.colouredEdge }
        if (useful.size < 5) {
            val needsReview = observations.any { it.fullBlack || it.colouredEdge || it.centreLuma < 27 }
            return CropDecision(
                kind = if (needsReview) CropKind.REVIEW else CropKind.NONE,
                note = when {
                    observations.any { it.fullBlack } -> "有效畫格不足，片頭或片尾可能是全黑，請人工確認"
                    observations.any { it.colouredEdge } -> "邊緣含有色彩或動態內容，未強制裁切"
                    observations.any { it.centreLuma < 27 } -> "畫面亮度過低，未把黑色內容誤當成黑邊"
                    else -> "沒有足夠的有效畫格可安全判斷黑邊"
                },
                sampleCount = observations.size,
                validSamples = useful.size
            )
        }
        val left = median(useful.map { it.left })
        val right = median(useful.map { it.right })
        val stable = useful.count {
            abs(it.left - left) <= 0.025f && abs(it.right - right) <= 0.025f
        }
        val minimumStable = ceil(useful.size * 0.70).toInt()
        val plausible = left in 0.045f..0.43f && right in 0.045f..0.43f && left + right < 0.78f
        val asymmetric = abs(left - right) > 0.10f
        val edgeContrast = useful.map { it.centreLuma - it.edgeLuma }.sorted()
            .getOrNull(useful.size / 2) ?: 0
        val confidence = (
            (stable * 100 / useful.size) * 0.55f +
                min(100f, edgeContrast * 2.2f) * 0.30f +
                if (!asymmetric) 15f else 0f
            ).toInt().coerceIn(0, 100)
        if (!plausible) {
            return CropDecision(
                left = left.coerceIn(0f, 0.45f),
                right = right.coerceIn(0f, 0.45f),
                kind = CropKind.REVIEW,
                note = "偵測到不尋常的黑邊比例，未自動勾選",
                confidence = confidence,
                validSamples = useful.size,
                sampleCount = observations.size
            )
        }
        val safeToApply = stable >= minimumStable && !asymmetric && edgeContrast >= 10
        return CropDecision(
            left = left,
            right = right,
            kind = if (safeToApply) CropKind.AUTO else CropKind.REVIEW,
            note = when {
                safeToApply -> "多時間點穩定偵測，已提出安全裁切建議"
                asymmetric -> "左右黑邊差異較大，請預覽確認後再裁切"
                stable < minimumStable -> "黑邊比例隨時間變化，未自動裁切"
                else -> "畫面亮度不足以安全確認，請人工確認"
            },
            confidence = confidence,
            validSamples = useful.size,
            sampleCount = observations.size,
            selected = safeToApply
        )
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}

object CropMath {
    fun outputDimension(source: Int, leading: Float, trailing: Float): Int {
        if (source <= 0) return 0
        val value = (source * (1f - leading - trailing)).toInt().coerceAtLeast(2)
        return value - value % 2
    }

    fun normalizedCrop(value: Float): Float = value.coerceIn(0f, 0.44f)
}
