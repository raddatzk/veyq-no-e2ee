import XCTest
import VeyqCrypto

// Native parity for gallery day-bucketing — byte-compatible with the web parser
// frontend/src/features/gallery/dateBuckets.ts (mirrors dateBuckets.test.ts). TZ-robust: same string → same
// key; 7-day-apart timestamps are a different local day in every time zone.
final class DayBucketsTests: XCTestCase {
    func testGroupsConsecutiveRunsAndReopensForNonConsecutive() {
        let d1 = "2024-06-13T12:00:00Z", d2 = "2024-06-20T12:00:00Z"
        let buckets = DayBuckets.groupByDay([d1, d1, d2, d1], takenAt: { $0 })
        XCTAssertEqual(buckets.count, 3)               // [d1,d1] · [d2] · [d1] — the returning d1 re-opens
        XCTAssertEqual(buckets[0].items.count, 2)
        XCTAssertEqual(buckets[1].items.count, 1)
        XCTAssertEqual(buckets[2].items.count, 1)
    }

    func testFallsBackToPlaceholderForInvalidTimestamps() {
        XCTAssertNil(DayBuckets.dayKey("not a date"))
        XCTAssertEqual(DayBuckets.groupByDay(["garbage"], takenAt: { $0 }).first?.key, "—")
    }

    func testDayKeyHasIsoDateShape() {
        XCTAssertEqual(DayBuckets.dayKey("2024-06-13T12:00:00Z")?.count, 10)  // YYYY-MM-DD
    }
}
