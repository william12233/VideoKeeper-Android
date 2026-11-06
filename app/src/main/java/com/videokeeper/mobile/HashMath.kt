package com.videokeeper.mobile

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Pure numeric matching for multi-frame perceptual fingerprints. */
object HashMath {
    fun distance(a: FrameSignature, b: FrameSignature): Int =
        java.lang.Long.bitCount(a.phash xor b.phash) + java.lang.Long.bitCount(a.dhash xor b.dhash)

    /**
     * Finds the best bounded temporal alignment. The return value is intentionally nullable:
     * an insufficient or contradictory sample set is "unable to analyse", not "different".
     */
    fun compare(a: List<FrameSignature>, b: List<FrameSignature>): SimilarityResult? {
        if (a.size < 5 || b.size < 5) return null
        var best: SimilarityResult? = null
        for (offset in -3..3) {
            val byIndex = b.associateBy { it.sampleIndex }
            val distances = mutableListOf<Int>()
            val colourDistances = mutableListOf<Int>()
            val lumaDistances = mutableListOf<Int>()
            a.forEach { left ->
                val right = byIndex[left.sampleIndex + offset] ?: return@forEach
                if (!left.usable || !right.usable) return@forEach
                distances += distance(left, right)
                if (left.color != 0 && right.color != 0) {
                    colourDistances += abs(left.color - right.color)
                }
                if (left.luma != 0 && right.luma != 0) {
                    lumaDistances += abs(left.luma - right.luma)
                }
            }
            if (distances.size < 5) continue
            val sorted = distances.sorted()
            val median = sorted[sorted.size / 2]
            val good = distances.count { it <= 34 }
            val medianColour = colourDistances.sorted().getOrNull(colourDistances.size / 2) ?: 0
            val medianLuma = lumaDistances.sorted().getOrNull(lumaDistances.size / 2) ?: 0
            val enoughGood = good >= ceil(distances.size * 0.65).toInt()
            val visualGate = medianColour <= 85 && medianLuma <= 72
            if (!enoughGood || median > 31 || !visualGate) continue
            val mean = distances.average()
            val score = (100.0 * (1.0 - mean / 128.0)).toInt().coerceIn(0, 100)
            val candidate = SimilarityResult(score, distances.size, offset, median)
            if (best == null || candidate.score > best!!.score) best = candidate
        }
        return best
    }

    // Kept as a small API for callers and compatibility with the first project tests.
    fun similarity(a: List<FrameSignature>, b: List<FrameSignature>): Int? = compare(a, b)?.score

    fun isDurationComparable(a: Long, b: Long): Boolean {
        if (a <= 0 || b <= 0) return false
        return abs(a - b) <= max(5_000L, (max(a, b) * 0.08).toLong())
    }
}
