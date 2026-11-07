package com.videokeeper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaModelUnitTest {
    @Test fun outputWidthIsEvenAndNeverNegative() {
        assertEquals(1282, CropMath.outputDimension(1920, .166f, .166f))
        assertEquals(2, CropMath.outputDimension(2, .44f, .44f))
        assertTrue(CropMath.normalizedCrop(.9f) <= .44f)
    }

    @Test fun temporalOffsetIsAcceptedOnlyWithEnoughFrames() {
        val a = List(10) { index -> FrameSignature(index.toLong(), (index * 3).toLong(), index) }
        val b = a.map { it.copy(sampleIndex = it.sampleIndex + 1) }
        val result = HashMath.compare(a, b)
        assertEquals(1, result?.offsetSamples)
        assertEquals(100, result?.score)
    }

    @Test fun fingerprintCodecRoundTripsExtendedFeatures() {
        val frames = listOf(FrameSignature(-1L, 42L, 3, 122, 17), FrameSignature(9L, -8L, 4, 90, 28, false))
        assertEquals(frames, FingerprintCodec.decode(FingerprintCodec.encode(frames)))
    }

    @Test fun aacAudioIsCopiedAndOtherAudioCanUseFallbackEncoding() {
        assertTrue(HardwareCodecInspector.canCopyAudio(HardwareCodecInspector.AAC))
        assertTrue(!HardwareCodecInspector.canCopyAudio("audio/opus"))
        assertTrue(!HardwareCodecInspector.canCopyAudio(null))
    }

    @Test fun encoderCropAlignsOddMedia3CropResultToEvenDimensions() {
        val crop = CropMath.encoderCrop(1280, 720, .342f, .373f)
        assertNotNull(crop)
        assertEquals(364, crop?.outputWidth)
        assertEquals(720, crop?.outputHeight)
        assertTrue(crop!!.outputWidth % 2 == 0)
        assertTrue(crop.outputHeight % 2 == 0)
    }

    @Test fun encoderCropAlsoFixesOddSourceHeightWithoutChangingHorizontalIntent() {
        val crop = CropMath.encoderCrop(641, 719, 0f, 0f)
        assertNotNull(crop)
        assertEquals(640, crop?.outputWidth)
        assertEquals(718, crop?.outputHeight)
        assertTrue(crop!!.bottom > 0f)
    }

    @Test fun manualCropSliderAllowsBothSidesUpToTheirOwnLimit() {
        assertEquals(.44f, CropMath.maxManualCropSide(.30f), .0001f)
        assertEquals(.40f, CropMath.clampManualCropSide(.40f, .30f), .0001f)
        assertTrue(CropMath.clampManualCropSide(.44f, .44f) + .44f < .88f)
    }
}
