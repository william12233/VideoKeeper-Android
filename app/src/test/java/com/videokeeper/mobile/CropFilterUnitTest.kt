package com.videokeeper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropFilterUnitTest {
    private data class Sample(val state: AnalysisState, val crop: CropDecision)

    @Test fun filtersExposeTheExpectedAnalysisStates() {
        val pending = Sample(AnalysisState.PENDING, CropDecision())
        val reviewing = Sample(
            AnalysisState.NEEDS_REVIEW,
            CropDecision(left = .18f, right = .18f, kind = CropKind.REVIEW)
        )
        val noCrop = Sample(AnalysisState.READY, CropDecision(kind = CropKind.NONE))
        val confirmed = Sample(
            AnalysisState.READY,
            CropDecision(left = .18f, right = .18f, kind = CropKind.AUTO, selected = true)
        )
        val unselected = Sample(
            AnalysisState.READY,
            CropDecision(left = .18f, right = .18f, kind = CropKind.AUTO, selected = false)
        )
        val failed = Sample(AnalysisState.UNSUPPORTED, CropDecision(kind = CropKind.UNSUPPORTED))
        val samples = listOf(pending, reviewing, noCrop, confirmed, unselected, failed)

        assertTrue(matchesCropFilter(pending.state, pending.crop, CropFilter.PENDING))
        assertTrue(matchesCropFilter(reviewing.state, reviewing.crop, CropFilter.REVIEW))
        assertTrue(matchesCropFilter(noCrop.state, noCrop.crop, CropFilter.NO_CROP))
        assertTrue(matchesCropFilter(confirmed.state, confirmed.crop, CropFilter.CONFIRMED))
        assertTrue(matchesCropFilter(unselected.state, unselected.crop, CropFilter.UNSELECTED))
        assertTrue(matchesCropFilter(failed.state, failed.crop, CropFilter.FAILED))
        assertEquals(6, samples.count { matchesCropFilter(it.state, it.crop, CropFilter.ALL) })
    }

    @Test fun unselectedFilterDoesNotIncludeUnanalysedItems() {
        val pending = Sample(
            AnalysisState.PENDING,
            CropDecision(left = .18f, right = .18f, kind = CropKind.REVIEW),
        )
        assertTrue(!matchesCropFilter(pending.state, pending.crop, CropFilter.UNSELECTED))
    }
}
