import XCTest
import VeyqCrypto

// Native parity for GPX parsing — byte-compatible with the web parser
// frontend/src/features/memory/track/gpx.ts (mirrors gpx.test.ts).
final class GpxTests: XCTestCase {
    private func data(_ s: String) -> Data { Data(s.utf8) }

    func testExtractsTrackNamePointsAndOptionalEleTime() {
        let gpx = "<gpx><trk><name>Morning hike</name><trkseg>"
            + "<trkpt lat=\"47.1\" lon=\"8.2\"><ele>500</ele><time>2024-01-01T10:00:00Z</time></trkpt>"
            + "<trkpt lat=\"47.2\" lon=\"8.3\"><ele>520</ele><time>2024-01-01T10:05:00Z</time></trkpt>"
            + "</trkseg></trk></gpx>"
        let route = Gpx.parse(data(gpx))
        XCTAssertNotNil(route)
        XCTAssertEqual(route?.name, "Morning hike")
        XCTAssertEqual(route?.points.count, 2)
        XCTAssertEqual(route?.points.first?.lat, 47.1)
        XCTAssertEqual(route?.points.first?.ele, 500)
        XCTAssertEqual(route?.points.first?.time, "2024-01-01T10:00:00Z")
    }

    func testFallsBackToRoutePointsAndGenericName() {
        let gpx = "<gpx><rte>"
            + "<rtept lat=\"47.1\" lon=\"8.2\"></rtept>"
            + "<rtept lat=\"47.2\" lon=\"8.3\"></rtept>"
            + "</rte></gpx>"
        let route = Gpx.parse(data(gpx))
        XCTAssertEqual(route?.points.count, 2)
        XCTAssertEqual(route?.name, "Track")  // generic fallback when no <name>
        XCTAssertNil(route?.points.first?.ele)
    }

    func testReturnsNilOnMalformedOrEmpty() {
        XCTAssertNil(Gpx.parse(data("<gpx><trk>")))   // malformed (unclosed)
        XCTAssertNil(Gpx.parse(data("<gpx></gpx>")))  // valid but no points
    }
}
