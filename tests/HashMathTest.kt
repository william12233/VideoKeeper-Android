package com.videokeeper.mobile

data class FrameSignature(val phash: Long, val dhash: Long, val sampleIndex: Int = 0)

fun main() {
    val base = List(12) { i -> FrameSignature(0x102F003A55AA5533L xor (i.toLong() * 19), 0x331155AA553300FFL xor (i.toLong() * 29), i) }
    val compressed = base.map { FrameSignature(it.phash xor 0x1110L, it.dhash xor 0x3021L, it.sampleIndex) }
    val distinct = base.map { FrameSignature(it.phash.inv(), it.dhash.inv(), it.sampleIndex) }
    check(HashMath.similarity(base, base) == 100)
    check((HashMath.similarity(base, compressed) ?: 0) >= 86)
    check(HashMath.similarity(base, distinct) == null)
    check(HashMath.isDurationComparable(60000L, 60400L))
    check(!HashMath.isDurationComparable(60000L, 67000L))
    println("HashMath tests PASS")
}
