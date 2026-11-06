package com.videokeeper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Basic pure-Kotlin comparison behavior; Android runtime is not required. */
class HashMathUnitTest {
    private val base = List(12) { i ->
        FrameSignature(0x102F003A55AA5533L xor (i.toLong() * 19),
            0x331155AA553300FFL xor (i.toLong() * 29), i)
    }

    @Test fun identicalVideoIsHundredPercent() {
        assertEquals(100, HashMath.similarity(base, base))
    }

    @Test fun smallTranscodeDifferenceCanStillMatch() {
        val compressed = base.map { FrameSignature(
            it.phash xor 0x1110L, it.dhash xor 0x3021L, it.sampleIndex) }
        assertTrue((HashMath.similarity(base, compressed) ?: 0) >= 86)
    }

    @Test fun differentVisualContentIsRejected() {
        val distinct = base.map { FrameSignature(it.phash.inv(), it.dhash.inv(), it.sampleIndex) }
        assertNull(HashMath.similarity(base, distinct))
    }

    @Test fun durationGateAvoidsLargeDifference() {
        assertTrue(HashMath.isDurationComparable(60000L, 60400L))
        assertFalse(HashMath.isDurationComparable(60000L, 67000L))
    }
}
