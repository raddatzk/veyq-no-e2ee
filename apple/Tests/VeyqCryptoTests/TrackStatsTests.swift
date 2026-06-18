import XCTest
import VeyqCrypto

// Native parity for track stats — byte-compatible with the web parser
// frontend/src/features/memory/track/trackStats.ts (mirrors trackStats.test.ts).
final class TrackStatsTests: XCTestCase {
    private func points(_ gpx: String) -> [Track.Point] { Gpx.parse(Data(gpx.utf8))?.points ?? [] }

    func testComputesDistanceWithoutEleOrTime() {
        let s = Track.stats(points("<gpx><trk><trkpt lat=\"47.0\" lon=\"8.0\"></trkpt><trkpt lat=\"47.0\" lon=\"8.1\"></trkpt></trk></gpx>"))
        XCTAssertGreaterThan(s.distanceM, 0)
        XCTAssertNil(s.ascentM)
        XCTAssertNil(s.descentM)
        XCTAssertNil(s.durationS)
    }

    func testSumsAscentAndDescentWithElevations() {
        let s = Track.stats(points("<gpx><trk>"
            + "<trkpt lat=\"47.0\" lon=\"8.0\"><ele>100</ele></trkpt>"
            + "<trkpt lat=\"47.0\" lon=\"8.001\"><ele>150</ele></trkpt>"
            + "<trkpt lat=\"47.0\" lon=\"8.002\"><ele>120</ele></trkpt>"
            + "</trk></gpx>"))
        XCTAssertEqual(s.ascentM, 50.0)
        XCTAssertEqual(s.descentM, 30.0)
    }

    func testDerivesDurationFromFirstAndLastTimestamp() {
        let s = Track.stats(points("<gpx><trk>"
            + "<trkpt lat=\"47.0\" lon=\"8.0\"><time>2024-01-01T10:00:00Z</time></trkpt>"
            + "<trkpt lat=\"47.0\" lon=\"8.001\"><time>2024-01-01T10:10:00Z</time></trkpt>"
            + "</trk></gpx>"))
        XCTAssertEqual(s.durationS, 600)
    }

    func testFormatsDistanceDurationAndElevation() {
        XCTAssertEqual(Track.formatDistance(500), "500 m")
        XCTAssertEqual(Track.formatDistance(1500), "1.5 km")
        XCTAssertEqual(Track.formatDuration(45), "45 s")
        XCTAssertEqual(Track.formatDuration(600), "10 min")
        XCTAssertEqual(Track.formatDuration(3660), "1 h 1 min")
        let elev = Track.stats(points("<gpx><trk>"
            + "<trkpt lat=\"47.0\" lon=\"8.0\"><ele>100</ele></trkpt>"
            + "<trkpt lat=\"47.0\" lon=\"8.001\"><ele>150</ele></trkpt>"
            + "<trkpt lat=\"47.0\" lon=\"8.002\"><ele>120</ele></trkpt>"
            + "</trk></gpx>"))
        XCTAssertEqual(Track.formatElevation(elev), "↑50 m ↓30 m")
        let flat = Track.stats(points("<gpx><trk><trkpt lat=\"47.0\" lon=\"8.0\"></trkpt><trkpt lat=\"47.0\" lon=\"8.1\"></trkpt></trk></gpx>"))
        XCTAssertEqual(Track.formatElevation(flat), "")
    }
}
