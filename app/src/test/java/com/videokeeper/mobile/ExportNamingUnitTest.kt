package com.videokeeper.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportNamingUnitTest {
    @Test fun separateDestinationKeepsOriginalFilename() {
        assertEquals(
            "原始影片.MP4",
            exportFilename("原始影片.MP4", sameFolder = false, uniqueToken = "ignored")
        )
    }

    @Test fun sameDestinationAddsCollisionSafeSuffix() {
        assertEquals(
            "原始影片_去黑邊_12345678.mp4",
            exportFilename("原始影片.MP4", sameFolder = true, uniqueToken = "12345678")
        )
    }
}
