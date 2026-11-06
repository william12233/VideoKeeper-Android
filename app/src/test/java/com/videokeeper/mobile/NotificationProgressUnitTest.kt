package com.videokeeper.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationProgressUnitTest {
    @Test fun completedItemsUseEqualQueueSegments() {
        assertEquals(0, aggregateQueueProgress(totalItems = 7, completedItems = 0, currentItemProgress = 0))
        assertEquals(14, aggregateQueueProgress(totalItems = 7, completedItems = 1, currentItemProgress = 0))
        assertEquals(42, aggregateQueueProgress(totalItems = 7, completedItems = 3, currentItemProgress = 0))
        assertEquals(100, aggregateQueueProgress(totalItems = 7, completedItems = 7, currentItemProgress = 0))
    }

    @Test fun currentItemProgressFillsOnlyItsQueueSegment() {
        assertEquals(7, aggregateQueueProgress(totalItems = 7, completedItems = 0, currentItemProgress = 50))
        assertEquals(50, aggregateQueueProgress(totalItems = 7, completedItems = 3, currentItemProgress = 50))
    }
}
