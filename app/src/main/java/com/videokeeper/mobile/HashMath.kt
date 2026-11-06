package com.videokeeper.mobile

import kotlin.math.abs

/** Pure numeric part of the perceptual fingerprint matcher; independent of Android. */
object HashMath {
    fun distance(a: FrameSignature, b: FrameSignature): Int =
        java.lang.Long.bitCount(a.phash xor b.phash) + java.lang.Long.bitCount(a.dhash xor b.dhash)

    // Compare at equal relative positions. A small one-frame slip is tolerated only after
    // a sufficient number of exact-index samples support the match.
    fun similarity(a: List<FrameSignature>, b: List<FrameSignature>): Int? {
        val byIndex = b.associateBy { it.sampleIndex }
        val distances = a.mapNotNull { left -> byIndex[left.sampleIndex]?.let { right -> distance(left, right) } }
        val n = distances.size
        if (n < 5) return null
        val middle = distances.sorted()[n / 2]
        val good = distances.count { it <= 30 }
        val mean = distances.sum().toDouble() / n
        if (good < (n * 0.65).toInt() || middle > 27) return null
        return (100.0 * (1.0 - mean / 128.0)).toInt().coerceIn(0, 100)
    }

    fun isDurationComparable(a: Long, b: Long): Boolean {
        if (a <= 0 || b <= 0) return false
        return abs(a - b) <= maxOf(1500L, (maxOf(a, b) * 0.045).toLong())
    }
}
