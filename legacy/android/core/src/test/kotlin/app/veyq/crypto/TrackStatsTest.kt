package app.veyq.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Native parity for track stats — byte-compatible with the web and iOS parsers
// (frontend/src/features/memory/track/trackStats.ts; mirrors trackStats.test.ts).
class TrackStatsTest {
    private fun points(gpx: String) = Gpx.parse(gpx.toByteArray())?.second ?: emptyList()

    @Test fun computesDistanceWithoutEleOrTime() {
        val s = Track.stats(points("<gpx><trk><trkpt lat=\"47.0\" lon=\"8.0\"></trkpt><trkpt lat=\"47.0\" lon=\"8.1\"></trkpt></trk></gpx>"))
        assertTrue(s.distanceM > 0)
        assertNull(s.ascentM)
        assertNull(s.descentM)
        assertNull(s.durationS)
    }

    @Test fun sumsAscentAndDescentWithElevations() {
        val s = Track.stats(points("<gpx><trk>" +
            "<trkpt lat=\"47.0\" lon=\"8.0\"><ele>100</ele></trkpt>" +
            "<trkpt lat=\"47.0\" lon=\"8.001\"><ele>150</ele></trkpt>" +
            "<trkpt lat=\"47.0\" lon=\"8.002\"><ele>120</ele></trkpt>" +
            "</trk></gpx>"))
        assertEquals(50.0, s.ascentM)
        assertEquals(30.0, s.descentM)
    }

    @Test fun derivesDurationFromFirstAndLastTimestamp() {
        val s = Track.stats(points("<gpx><trk>" +
            "<trkpt lat=\"47.0\" lon=\"8.0\"><time>2024-01-01T10:00:00Z</time></trkpt>" +
            "<trkpt lat=\"47.0\" lon=\"8.001\"><time>2024-01-01T10:10:00Z</time></trkpt>" +
            "</trk></gpx>"))
        assertEquals(600L, s.durationS)
    }

    @Test fun formatsDistanceDurationAndElevation() {
        assertEquals("500 m", Track.formatDistance(500.0))
        assertEquals("1.5 km", Track.formatDistance(1500.0))
        assertEquals("45 s", Track.formatDuration(45L))
        assertEquals("10 min", Track.formatDuration(600L))
        assertEquals("1 h 1 min", Track.formatDuration(3660L))
        val elev = Track.stats(points("<gpx><trk>" +
            "<trkpt lat=\"47.0\" lon=\"8.0\"><ele>100</ele></trkpt>" +
            "<trkpt lat=\"47.0\" lon=\"8.001\"><ele>150</ele></trkpt>" +
            "<trkpt lat=\"47.0\" lon=\"8.002\"><ele>120</ele></trkpt>" +
            "</trk></gpx>"))
        assertEquals("↑50 m ↓30 m", Track.formatElevation(elev))
        val flat = Track.stats(points("<gpx><trk><trkpt lat=\"47.0\" lon=\"8.0\"></trkpt><trkpt lat=\"47.0\" lon=\"8.1\"></trkpt></trk></gpx>"))
        assertEquals("", Track.formatElevation(flat))
    }
}
