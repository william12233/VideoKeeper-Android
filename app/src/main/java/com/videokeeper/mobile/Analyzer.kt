package com.videokeeper.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Low-memory, cancellation-aware media sampler used by both crop and duplicate analysis. */
class Analyzer(private val context: Context) {
    companion object {
        const val ALGORITHM_VERSION = 3
        private const val SAMPLE_COUNT = 15
    }

    private val database by lazy { FingerprintDatabase.get(context) }

    fun readInfo(item: VideoEntry): VideoInfo = readInfo(item.uri)

    fun readInfo(uri: Uri): VideoInfo {
        val retriever = open(uri)
        var durationMs = 0L
        var width = 0
        var height = 0
        var rotation = 0
        try {
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()?.let { ((it % 360) + 360) % 360 } ?: 0
        } finally {
            retriever.release()
        }
        var videoMime: String? = null
        var audioMime: String? = null
        var bitrate = 0
        var frameRate = 0f
        var frameCount = 0L
        var extractor: MediaExtractor? = null
        try {
            extractor = MediaExtractor()
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                extractor!!.setDataSource(descriptor.fileDescriptor)
                for (index in 0 until extractor!!.trackCount) {
                    val format = extractor!!.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/")) {
                        videoMime = mime
                        if (width <= 0 && format.containsKey(MediaFormat.KEY_WIDTH)) {
                            width = format.getInteger(MediaFormat.KEY_WIDTH)
                        }
                        if (height <= 0 && format.containsKey(MediaFormat.KEY_HEIGHT)) {
                            height = format.getInteger(MediaFormat.KEY_HEIGHT)
                        }
                        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                        }
                        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                            bitrate = format.getInteger(MediaFormat.KEY_BIT_RATE)
                        }
                        if (durationMs <= 0 && format.containsKey(MediaFormat.KEY_DURATION)) {
                            durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1_000L
                        }
                    } else if (mime.startsWith("audio/")) {
                        audioMime = mime
                    }
                }
            }
        } catch (_: Exception) {
            // Metadata is still useful when a provider cannot expose an extractor FD.
        } finally {
            extractor?.release()
        }
        return VideoInfo(
            durationMs = durationMs,
            width = width,
            height = height,
            rotationDegrees = rotation,
            videoMime = videoMime,
            audioMime = audioMime,
            bitrate = bitrate,
            frameRate = frameRate,
            hasAudio = audioMime != null,
            videoFrameCount = frameCount
        )
    }

    fun detectCrop(
        item: VideoEntry,
        isCancelled: () -> Boolean = { false },
        onSample: (Int, Int) -> Unit = { _, _ -> }
    ): CropDecision {
        val info = item.info ?: readInfo(item)
        if (info.durationMs < 900L) {
            return CropDecision(kind = CropKind.REVIEW, note = "影片太短，請人工確認")
        }
        val retriever = open(item.uri)
        val observations = mutableListOf<BorderObservation>()
        try {
            repeat(SAMPLE_COUNT) { index ->
                if (isCancelled()) return@repeat
                val fraction = 0.04 + 0.92 * index / (SAMPLE_COUNT - 1).toDouble()
                val timeUs = (info.durationMs * 1_000L * fraction).toLong()
                val frame = runCatching {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        480,
                        480
                    )
                }.getOrNull()
                if (frame != null) {
                    try {
                        observeBorders(frame)?.let { observations += it }
                    } finally {
                        frame.recycle()
                    }
                }
                onSample(index + 1, SAMPLE_COUNT)
            }
        } finally {
            retriever.release()
        }
        return BlackBarDetector.decide(info.width, info.height, info.rotationDegrees, observations)
    }

    fun fingerprint(
        item: VideoEntry,
        isCancelled: () -> Boolean = { false },
        onSample: (Int, Int) -> Unit = { _, _ -> }
    ): List<FrameSignature> {
        val info = item.info ?: readInfo(item)
        if (info.durationMs < 1_000L || info.width <= 0 || info.height <= 0) return emptyList()
        val cacheKey = cacheKey(item, info)
        database.fingerprintDao().find(cacheKey)?.let { cached ->
            val frames = FingerprintCodec.decode(cached.samples)
            if (frames.size >= 5) return frames
        }
        val detected = runCatching { detectCrop(item, isCancelled) }.getOrNull()
        val safeCrop = if (detected?.kind == CropKind.AUTO) detected else CropDecision()
        val retriever = open(item.uri)
        val frames = mutableListOf<FrameSignature>()
        try {
            repeat(12) { index ->
                if (isCancelled()) return@repeat
                val fraction = 0.08 + 0.84 * index / 11.0
                val frame = runCatching {
                    retriever.getScaledFrameAtTime(
                        (info.durationMs * 1_000L * fraction).toLong(),
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        320,
                        320
                    )
                }.getOrNull()
                if (frame != null) {
                    try {
                        val working = cropBitmap(frame, safeCrop)
                        try {
                            frameSignature(working)?.let { frames += it.copy(sampleIndex = index) }
                        } finally {
                            if (working !== frame) working.recycle()
                        }
                    } finally {
                        frame.recycle()
                    }
                }
                onSample(index + 1, 12)
            }
        } finally {
            retriever.release()
        }
        if (frames.size >= 5) {
            database.fingerprintDao().put(
                FingerprintEntity(
                    cacheKey = cacheKey,
                    uri = item.uri.toString(),
                    bytes = item.bytes,
                    modified = item.modified,
                    durationMs = info.durationMs,
                    width = info.width,
                    height = info.height,
                    algorithmVersion = ALGORITHM_VERSION,
                    samples = FingerprintCodec.encode(frames),
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        return frames
    }

    /** Verifies candidates with low-resolution SSIM-like frame comparisons and bounded offsets. */
    fun verifyPair(
        first: VideoEntry,
        second: VideoEntry,
        isCancelled: () -> Boolean = { false }
    ): VerificationResult? {
        val firstInfo = first.info ?: readInfo(first)
        val secondInfo = second.info ?: readInfo(second)
        if (!HashMath.isDurationComparable(firstInfo.durationMs, secondInfo.durationMs)) return null
        val leftRetriever = open(first.uri)
        val rightRetriever = open(second.uri)
        val fractions = listOf(0.10, 0.22, 0.36, 0.50, 0.64, 0.78, 0.90)
        var best: VerificationResult? = null
        try {
            for (offset in -2..2) {
                val scores = mutableListOf<Double>()
                for (index in fractions.indices) {
                    if (isCancelled()) return best
                    val rightIndex = index + offset
                    if (rightIndex !in fractions.indices) continue
                    val leftFrame = frameAt(leftRetriever, firstInfo, fractions[index]) ?: continue
                    val rightFrame = frameAt(rightRetriever, secondInfo, fractions[rightIndex]) ?: run {
                        leftFrame.recycle()
                        continue
                    }
                    try {
                        val a = cropBitmap(leftFrame, first.crop.takeIf { it.cropped && it.selected } ?: CropDecision())
                        val b = cropBitmap(rightFrame, second.crop.takeIf { it.cropped && it.selected } ?: CropDecision())
                        try {
                            val score = ssimLike(a, b)
                            if (score >= 0.58) scores += score
                        } finally {
                            if (a !== leftFrame) a.recycle()
                            if (b !== rightFrame) b.recycle()
                        }
                    } finally {
                        leftFrame.recycle()
                        rightFrame.recycle()
                    }
                }
                if (scores.size >= 4) {
                    val score = (scores.average() * 100).toInt().coerceIn(0, 100)
                    if (best == null || score > best!!.score) {
                        best = VerificationResult(score, scores.size, offset)
                    }
                }
            }
        } finally {
            leftRetriever.release()
            rightRetriever.release()
        }
        return best
    }

    fun preview(uri: Uri, atFraction: Float = 0.5f): Bitmap? {
        val retriever = open(uri)
        return try {
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val raw = retriever.getScaledFrameAtTime(
                (duration * 1_000L * atFraction.coerceIn(0f, 1f)).toLong(),
                MediaMetadataRetriever.OPTION_CLOSEST,
                960,
                960
            ) ?: return null
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            rotate(raw, rotation)
        } finally {
            retriever.release()
        }
    }

    fun clearFingerprintCache() {
        database.fingerprintDao().clear()
    }

    fun fingerprintCacheCount(): Int = database.fingerprintDao().count()

    private fun open(uri: Uri): MediaMetadataRetriever = MediaMetadataRetriever().apply {
        setDataSource(context, uri)
    }

    private fun frameAt(
        retriever: MediaMetadataRetriever,
        info: VideoInfo,
        fraction: Double
    ): Bitmap? = runCatching {
        retriever.getScaledFrameAtTime(
            (info.durationMs * 1_000L * fraction).toLong(),
            MediaMetadataRetriever.OPTION_CLOSEST,
            240,
            240
        )
    }.getOrNull()

    private fun observeBorders(bitmap: Bitmap): BorderObservation? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 80 || height < 50 || width <= height) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val rows = intArrayOf(
            (height * 0.16f).toInt(),
            (height * 0.29f).toInt(),
            (height * 0.50f).toInt(),
            (height * 0.71f).toInt(),
            (height * 0.84f).toInt()
        )
        fun rgb(pixel: Int): Triple<Int, Int, Int> = Triple(
            pixel shr 16 and 255,
            pixel shr 8 and 255,
            pixel and 255
        )
        fun luma(pixel: Int): Int {
            val (r, g, b) = rgb(pixel)
            return (r * 299 + g * 587 + b * 114) / 1000
        }
        fun chroma(pixel: Int): Int {
            val (r, g, b) = rgb(pixel)
            return max(r, max(g, b)) - min(r, min(g, b))
        }
        val centreStart = (width * 0.38f).toInt().coerceAtLeast(0)
        val centreEnd = (width * 0.62f).toInt().coerceAtMost(width - 1)
        val centre = rows.flatMap { y ->
            (centreStart..centreEnd)
                .filter { x -> (x - (width * 0.50f)).toInt() % 7 == 0 }
                .map { x -> luma(pixels[y.coerceIn(0, height - 1) * width + x]) }
        }.sorted()
        if (centre.isEmpty()) return null
        val centreMedian = centre[centre.size / 2]
        val fullBlack = centreMedian < 24 && centre.last() < 40
        val threshold = min(48, max(25, centreMedian - 7))
        fun isBlack(pixel: Int): Boolean = luma(pixel) <= threshold && chroma(pixel) <= 28
        fun darkColumn(x: Int): Boolean {
            val dark = rows.count { y -> isBlack(pixels[y.coerceIn(0, height - 1) * width + x]) }
            return dark >= 4
        }
        val limit = (width * 0.44f).toInt()
        var leftPx = 0
        while (leftPx < limit && darkColumn(leftPx)) leftPx++
        var rightPx = 0
        while (rightPx < limit && darkColumn(width - 1 - rightPx)) rightPx++
        val edgePixels = rows.map { y ->
            val left = pixels[y.coerceIn(0, height - 1) * width]
            val right = pixels[y.coerceIn(0, height - 1) * width + width - 1]
            (left to right)
        }
        val colouredEdge = edgePixels.count { (left, right) ->
            (luma(left) < threshold + 12 && chroma(left) > 35) ||
                (luma(right) < threshold + 12 && chroma(right) > 35)
        } >= 3
        val edgeWidth = max(1, min(width / 20, max(leftPx, rightPx)))
        val edgeLuma = rows.map { y ->
            val left = pixels[y.coerceIn(0, height - 1) * width]
            val right = pixels[y.coerceIn(0, height - 1) * width + width - 1]
            (luma(left) + luma(right)) / 2
        }.average().toInt()
        val detected = leftPx >= width * 0.045f || rightPx >= width * 0.045f
        val valid = !fullBlack && !colouredEdge && centreMedian >= 27 && detected
        return BorderObservation(
            left = leftPx.toFloat() / width,
            right = rightPx.toFloat() / width,
            centreLuma = centreMedian,
            edgeLuma = edgeLuma,
            usable = valid,
            fullBlack = fullBlack,
            colouredEdge = colouredEdge
        )
    }

    private fun cropBitmap(bitmap: Bitmap, crop: CropDecision): Bitmap {
        if (!crop.cropped) return bitmap
        val left = (bitmap.width * crop.left).toInt().coerceIn(0, bitmap.width - 2)
        val right = (bitmap.width * (1f - crop.right)).toInt().coerceIn(left + 2, bitmap.width)
        return Bitmap.createBitmap(bitmap, left, 0, right - left, bitmap.height)
    }

    private fun frameSignature(src: Bitmap): FrameSignature? {
        val small = Bitmap.createScaledBitmap(src, 32, 32, true)
        val pixels = IntArray(32 * 32)
        try {
            small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        } finally {
            if (small !== src) small.recycle()
        }
        val grey = DoubleArray(1024)
        var lumaTotal = 0.0
        var saturationTotal = 0.0
        pixels.forEachIndexed { index, pixel ->
            val r = pixel shr 16 and 255
            val g = pixel shr 8 and 255
            val b = pixel and 255
            grey[index] = r * 0.299 + g * 0.587 + b * 0.114
            lumaTotal += grey[index]
            saturationTotal += max(r, max(g, b)) - min(r, min(g, b))
        }
        val mean = grey.average()
        val variance = grey.map { (it - mean) * (it - mean) }.average()
        if (mean < 24.0 && variance < 160.0) return null
        val tmp = Array(8) { DoubleArray(32) }
        val cosTable = Array(8) { k ->
            DoubleArray(32) { x -> cos((2 * x + 1) * k * Math.PI / 64) }
        }
        for (u in 0..7) for (y in 0..31) for (x in 0..31) {
            tmp[u][y] += grey[y * 32 + x] * cosTable[u][x]
        }
        val coeff = DoubleArray(64)
        for (u in 0..7) for (v in 0..7) for (y in 0..31) {
            coeff[u * 8 + v] += tmp[u][y] * cosTable[v][y]
        }
        val median = coeff.drop(1).sorted()[31]
        var phash = 0L
        for (index in coeff.indices) if (coeff[index] >= median) phash = phash or (1L shl index)
        val tiny = Bitmap.createScaledBitmap(src, 9, 8, true)
        val tinyPixels = IntArray(9 * 8)
        try {
            tiny.getPixels(tinyPixels, 0, 9, 0, 0, 9, 8)
        } finally {
            if (tiny !== src) tiny.recycle()
        }
        fun luminance(pixel: Int): Int =
            (pixel shr 16 and 255) * 3 + (pixel shr 8 and 255) * 6 + (pixel and 255)
        var dhash = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (luminance(tinyPixels[y * 9 + x]) > luminance(tinyPixels[y * 9 + x + 1])) {
                dhash = dhash or (1L shl bit)
            }
            bit++
        }
        return FrameSignature(
            phash = phash,
            dhash = dhash,
            luma = (lumaTotal / pixels.size).toInt(),
            color = (saturationTotal / pixels.size).toInt()
        )
    }

    private fun ssimLike(a: Bitmap, b: Bitmap): Double {
        val width = 48
        val height = 48
        val aa = Bitmap.createScaledBitmap(a, width, height, true)
        val bb = Bitmap.createScaledBitmap(b, width, height, true)
        val ap = IntArray(width * height)
        val bp = IntArray(width * height)
        try {
            aa.getPixels(ap, 0, width, 0, 0, width, height)
            bb.getPixels(bp, 0, width, 0, 0, width, height)
        } finally {
            if (aa !== a) aa.recycle()
            if (bb !== b) bb.recycle()
        }
        fun y(pixel: Int): Double =
            ((pixel shr 16 and 255) * 0.299 + (pixel shr 8 and 255) * 0.587 +
                (pixel and 255) * 0.114)
        val x = ap.map(::y).average()
        val z = bp.map(::y).average()
        var covariance = 0.0
        var varianceA = 0.0
        var varianceB = 0.0
        ap.indices.forEach { index ->
            val da = y(ap[index]) - x
            val db = y(bp[index]) - z
            covariance += da * db
            varianceA += da * da
            varianceB += db * db
        }
        val n = ap.size.toDouble()
        val c1 = 6.5025
        val c2 = 58.5225
        return (((2 * x * z + c1) * (2 * (covariance / n) + c2)) /
            ((x * x + z * z + c1) * (varianceA / n + varianceB / n + c2)))
            .coerceIn(-1.0, 1.0)
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return bitmap
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun cacheKey(item: VideoEntry, info: VideoInfo): String {
        val raw = listOf(
            ALGORITHM_VERSION,
            item.uri.toString(),
            item.bytes,
            item.modified,
            info.durationMs,
            info.width,
            info.height,
            info.rotationDegrees
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { byte ->
            "%02x".format(byte)
        }
    }
}
