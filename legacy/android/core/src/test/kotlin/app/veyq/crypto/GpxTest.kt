package app.veyq.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Native parity for GPX parsing — byte-compatible with the web and iOS parsers
// (frontend/src/features/memory/track/gpx.ts; mirrors gpx.test.ts).
class GpxTest {
    private fun bytes(s: String) = s.toByteArray()

    @Test fun extractsTrackNamePointsAndOptionalEleTime() {
        val gpx = "<gpx><trk><name>Morning hike</name><trkseg>" +
            "<trkpt lat=\"47.1\" lon=\"8.2\"><ele>500</ele><time>2024-01-01T10:00:00Z</time></trkpt>" +
            "<trkpt lat=\"47.2\" lon=\"8.3\"><ele>520</ele><time>2024-01-01T10:05:00Z</time></trkpt>" +
            "</trkseg></trk></gpx>"
        val r = Gpx.parse(bytes(gpx))
        assertNotNull(r)
        assertEquals("Morning hike", r.first)
        assertEquals(2, r.second.size)
        assertEquals(47.1, r.second[0].lat)
        assertEquals(500.0, r.second[0].ele)
        assertEquals("2024-01-01T10:00:00Z", r.second[0].time)
    }

    @Test fun fallsBackToRoutePointsAndGenericName() {
        val gpx = "<gpx><rte>" +
            "<rtept lat=\"47.1\" lon=\"8.2\"></rtept>" +
            "<rtept lat=\"47.2\" lon=\"8.3\"></rtept>" +
            "</rte></gpx>"
        val r = Gpx.parse(bytes(gpx))!!
        assertEquals(2, r.second.size)
        assertEquals("Track", r.first)  // generic fallback when no <name>
        assertNull(r.second[0].ele)
    }

    @Test fun returnsNullOnMalformedOrEmpty() {
        assertNull(Gpx.parse(bytes("<gpx><trk>")))   // malformed (unclosed)
        assertNull(Gpx.parse(bytes("<gpx></gpx>")))  // valid but no points
    }
}
