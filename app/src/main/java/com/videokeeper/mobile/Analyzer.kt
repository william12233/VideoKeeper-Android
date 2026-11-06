package com.videokeeper.mobile

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.security.MessageDigest
import kotlin.math.*

class Analyzer(private val context: Context) {
    private val prefs = context.getSharedPreferences("frame_signatures_v1", Context.MODE_PRIVATE)

    private fun open(uri: Uri): MediaMetadataRetriever = MediaMetadataRetriever().apply {
        setDataSource(context, uri)
    }

    fun readInfo(item: VideoEntry) {
        val r = open(item.uri)
        try {
            item.durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            item.width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            item.height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        } finally { r.release() }
    }

    fun detectCrop(item: VideoEntry): CropDecision {
        val r = open(item.uri)
        try {
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            item.durationMs = duration
            item.width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            item.height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (duration < 1200) return CropDecision(kind = CropKind.REVIEW, note = "影片太短，請手動確認")
            if (rotation == 90 || rotation == 270 || item.width <= item.height) {
                return CropDecision(kind = CropKind.NONE, note = "影片本身已是直式或具有旋轉資訊")
            }
            val positions = mutableListOf<Pair<Float, Float>>()
            val count = 13
            repeat(count) { i ->
                val time = ((0.08 + 0.84 * i / (count - 1)) * duration * 1000).toLong()
                val bitmap = try { r.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 384, 216) }
                             catch (_: Exception) { null }
                if (bitmap != null) {
                    try { detectBorders(bitmap)?.let { positions.add(it) } }
                    finally { bitmap.recycle() }
                }
            }
            if (positions.size < 5) return CropDecision(kind = CropKind.NONE, note = "沒有找到穩定的左右純黑邊")
            val left = positions.map { it.first }.sorted()[positions.size / 2]
            val right = positions.map { it.second }.sorted()[positions.size / 2]
            val stable = positions.count { abs(it.first - left) < 0.035 && abs(it.second - right) < 0.035 }
            val balanced = abs(left - right) <= 0.065f
            val safe = left in 0.055f..0.43f && right in 0.055f..0.43f && left + right < 0.80f
            if (!safe) return CropDecision(kind = CropKind.REVIEW, note = "黑邊比例異常，需人工確認")
            val auto = stable >= 8 && positions.size >= 9 && balanced
            return CropDecision(
                left = left,
                right = right,
                kind = if (auto) CropKind.AUTO else CropKind.REVIEW,
                note = if (auto) "多時間點偵測一致，建議裁切" else "偵測到黑邊，但一致性不足，請預覽確認",
                selected = auto
            )
        } finally { r.release() }
    }

    /** Returns the share of black padding at left and right, or null if ambiguous. */
    private fun detectBorders(bitmap: Bitmap): Pair<Float, Float>? {
        // Resize only when required. The retriever already returns a lightweight frame.
        val w = bitmap.width; val h = bitmap.height
        if (w < 80 || h < 50 || w <= h) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val ys = (0 until 21).map { ((0.1 + 0.8 * it / 20) * (h - 1)).toInt() }
        fun darkColumn(x: Int): Boolean {
            var dark = 0
            for (y in ys) {
                val c = pixels[y * w + x]
                val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
                if (maxOf(r, g, b) <= 37 && (r * 3 + g * 6 + b) <= 260) dark++
            }
            return dark >= 19
        }
        val limit = (w * 0.44).toInt()
        var l = 0
        while (l < limit && darkColumn(l)) l++
        var r = 0
        while (r < limit && darkColumn(w - 1 - r)) r++
        if (l < w * 0.055 || r < w * 0.055 || l + r > w * 0.8) return null
        // Reject almost-black scenes that could be mistaken for letterboxing.
        var brightness = 0L; var count = 0
        for (sampleIndex in ys.indices step 2) {
            val y = ys[sampleIndex]
            for (x in l + 3 until w - r - 3 step 7) {
                val c = pixels[y * w + x]
                brightness += ((c shr 16 and 255) + (c shr 8 and 255) + (c and 255)) / 3
                count++
            }
        }
        if (count == 0 || brightness / count < 25L) return null
        return Pair(l.toFloat() / w, r.toFloat() / w)
    }

    fun fingerprint(item: VideoEntry): List<FrameSignature> {
        if (item.durationMs == 0L) readInfo(item)
        val key = cacheKey(item)
        val cached = prefs.getString(key, null)
        if (cached != null) {
            val frames = cached.split(';').mapNotNull { part ->
                val split = part.split(':')
                if (split.size == 3) {
                    try { FrameSignature(split[1].toULong(16).toLong(), split[2].toULong(16).toLong(), split[0].toInt()) }
                    catch (_: Exception) { null }
                } else null
            }
            if (frames.size >= 5) { item.fingerprint = frames; return frames }
        }
        if (item.durationMs < 2500) return emptyList()
        val crop = if (item.crop.kind == CropKind.AUTO || (item.crop.kind == CropKind.REVIEW && item.crop.selected)) {
            item.crop
        } else detectCrop(item)
        val r = open(item.uri)
        try {
            val duration = item.durationMs
            val frames = mutableListOf<FrameSignature>()
            val count = 12
            repeat(count) { i ->
                val t = ((0.08 + 0.84 * i / (count - 1)) * duration * 1000L).toLong()
                val frame = try { r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, 256, 256) }
                            catch (_: Exception) { null }
                if (frame != null) {
                    try {
                        var working = frame
                        if (crop.cropped && (crop.kind == CropKind.AUTO || crop.selected)) {
                            val from = (frame.width * crop.left).roundToInt().coerceIn(0, frame.width - 2)
                            val to = (frame.width * (1 - crop.right)).roundToInt().coerceIn(from + 1, frame.width)
                            working = Bitmap.createBitmap(frame, from, 0, to - from, frame.height)
                        }
                        try { frameSignature(working)?.let { frames.add(it.copy(sampleIndex = i)) } }
                        finally { if (working !== frame) working.recycle() }
                    } finally { frame.recycle() }
                }
            }
            item.fingerprint = frames
            if (frames.size >= 5) prefs.edit().putString(key, frames.joinToString(";") {
                it.sampleIndex.toString() + ":" + it.phash.toULong().toString(16) + ":" + it.dhash.toULong().toString(16)
            }).apply()
            return frames
        } finally { r.release() }
    }

    fun preview(uri: Uri, atFraction: Float = 0.5f): Bitmap? {
        val r = open(uri)
        return try {
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.getScaledFrameAtTime((duration * 1000 * atFraction).toLong(),
                MediaMetadataRetriever.OPTION_CLOSEST, 720, 720)
        } finally { r.release() }
    }

    private fun cacheKey(item: VideoEntry): String {
        val bytes = (item.uri.toString() + "|" + item.bytes + "|" + item.modified + "|" + item.durationMs).toByteArray()
        val cropKey = if (item.crop.selected) "|crop:${item.crop.left}:${item.crop.right}" else ""
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes + cropKey.toByteArray())
        return "f2_" + digest.joinToString("") { "%02x".format(it) }
    }

    private val cosTable = Array(8) { k -> DoubleArray(32) { x -> cos((2 * x + 1) * k * Math.PI / 64) } }

    private fun frameSignature(src: Bitmap): FrameSignature? {
        val small = Bitmap.createScaledBitmap(src, 32, 32, true)
        val bitmap = IntArray(32 * 32)
        try { small.getPixels(bitmap, 0, 32, 0, 0, 32, 32) }
        finally { if (small !== src) small.recycle() }
        val grey = DoubleArray(1024)
        for (i in bitmap.indices) {
            val c = bitmap[i]
            grey[i] = ((c shr 16 and 255) * 0.299 + (c shr 8 and 255) * 0.587 + (c and 255) * 0.114)
        }
        val mean = grey.average()
        if (grey.map { (it - mean) * (it - mean) }.average() < 100) return null
        val tmp = Array(8) { DoubleArray(32) }
        for (u in 0..7) for (y in 0..31) for (x in 0..31) tmp[u][y] += grey[y * 32 + x] * cosTable[u][x]
        val coeff = DoubleArray(64)
        for (u in 0..7) for (v in 0..7) for (y in 0..31) coeff[u * 8 + v] += tmp[u][y] * cosTable[v][y]
        val median = coeff.drop(1).sorted()[31]
        var ph = 0L
        for (i in 0 until 64) if (coeff[i] >= median) ph = ph or (1L shl i)
        val tiny = Bitmap.createScaledBitmap(src, 9, 8, true)
        val p = IntArray(9 * 8)
        try { tiny.getPixels(p, 0, 9, 0, 0, 9, 8) }
        finally { if (tiny !== src) tiny.recycle() }
        fun lum(c: Int) = (c shr 16 and 255) * 3 + (c shr 8 and 255) * 6 + (c and 255)
        var dh = 0L
        var bit = 0
        for (y in 0..7) for (x in 0..7) {
            if (lum(p[y * 9 + x]) > lum(p[y * 9 + x + 1])) dh = dh or (1L shl bit)
            bit++
        }
        return FrameSignature(ph, dh)
    }
}
