package app.veyq.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Native parity for gallery day-bucketing — byte-compatible with the web and iOS versions
// (frontend/src/features/gallery/dateBuckets.ts; mirrors dateBuckets.test.ts). TZ-robust.
class DayBucketsTest {
    @Test fun groupsConsecutiveRunsAndReopensForNonConsecutive() {
        val d1 = "2024-06-13T12:00:00Z"; val d2 = "2024-06-20T12:00:00Z"
        val buckets = DayBuckets.groupByDay(listOf(d1, d1, d2, d1)) { it }
        assertEquals(3, buckets.size)                 // [d1,d1] · [d2] · [d1] — the returning d1 re-opens
        assertEquals(2, buckets[0].second.size)
        assertEquals(1, buckets[1].second.size)
        assertEquals(1, buckets[2].second.size)
    }

    @Test fun fallsBackToPlaceholderForInvalidTimestamps() {
        assertNull(DayBuckets.dayKey("not a date"))
        assertEquals("—", DayBuckets.groupByDay(listOf("garbage")) { it }.first().first)
    }

    @Test fun dayKeyHasIsoDateShape() {
        val key = DayBuckets.dayKey("2024-06-13T12:00:00Z")
        assertNotNull(key)
        assertEquals(10, key.length)  // YYYY-MM-DD
    }
}
