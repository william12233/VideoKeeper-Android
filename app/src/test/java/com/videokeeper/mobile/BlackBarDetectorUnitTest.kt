package com.videokeeper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackBarDetectorUnitTest {
    @Test fun stableBlackBarsBecomeAutomaticSuggestion() {
        val samples = List(12) { BorderObservation(.18f, .20f, 118, 3, usable = true) }
        val result = BlackBarDetector.decide(1920, 1080, 0, samples)
        assertEquals(CropKind.AUTO, result.kind)
        assertTrue(result.selected)
        assertEquals(18, (result.left * 100).toInt())
    }

    @Test fun fullBlackIntroIsNotUsedAsCropEvidence() {
        val samples = List(8) { BorderObservation(.20f, .20f, 0, 0, usable = false, fullBlack = true) } +
            List(4) { BorderObservation(.20f, .20f, 116, 2, usable = true) }
        val result = BlackBarDetector.decide(1920, 1080, 0, samples)
        assertEquals(CropKind.REVIEW, result.kind)
        assertFalse(result.selected)
    }

    @Test fun asymmetricBarsRequireHumanConfirmation() {
        val samples = List(12) { BorderObservation(.08f, .25f, 100, 2, usable = true) }
        val result = BlackBarDetector.decide(1920, 1080, 0, samples)
        assertEquals(CropKind.REVIEW, result.kind)
        assertFalse(result.selected)
    }

    @Test fun noBarsAndPortraitSourceAreNotForcedToCrop() {
        val noBars = List(10) { BorderObservation(0f, 0f, 120, 118, usable = false) }
        assertEquals(CropKind.NONE, BlackBarDetector.decide(1920, 1080, 0, noBars).kind)
        assertEquals(CropKind.NONE, BlackBarDetector.decide(1080, 1920, 0, noBars).kind)
    }

    @Test fun rotationMetadataRequiresReview() {
        val samples = List(10) { BorderObservation(.18f, .18f, 100, 2, usable = true) }
        val result = BlackBarDetector.decide(1920, 1080, 90, samples)
        assertEquals(CropKind.REVIEW, result.kind)
        assertFalse(result.selected)
    }

    @Test fun unstableDynamicBackgroundIsNotAutoSelected() {
        val samples = listOf(
            BorderObservation(.05f, .05f, 100, 10, true),
            BorderObservation(.22f, .18f, 105, 3, true),
            BorderObservation(.04f, .28f, 98, 4, true),
            BorderObservation(.21f, .19f, 110, 2, true),
            BorderObservation(.06f, .06f, 102, 9, true),
            BorderObservation(.25f, .20f, 106, 4, true),
            BorderObservation(.05f, .05f, 104, 8, true),
            BorderObservation(.20f, .20f, 108, 2, true)
        )
        assertEquals(CropKind.REVIEW, BlackBarDetector.decide(1920, 1080, 0, samples).kind)
    }

    @Test fun lowLightContentIsNotCalledNoBarsWithConfidence() {
        val samples = List(10) { BorderObservation(.18f, .18f, 25, 2, usable = false) }
        val result = BlackBarDetector.decide(1920, 1080, 0, samples)
        assertEquals(CropKind.REVIEW, result.kind)
        assertTrue(result.note.contains("亮度"))
    }
}
