package com.veyq.api.library.track

import com.fasterxml.jackson.databind.ObjectMapper
import com.veyq.api.auth.BadRequestException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Instant

class GpxTrackParserTest {
    private val parser = GpxTrackParser(ObjectMapper())

    @Test
    fun `parses route metadata from gpx track points`() {
        val file = Files.createTempFile("veyq-track-", ".gpx")
        try {
            Files.writeString(
                file,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <gpx version="1.1" creator="veyq-test" xmlns="http://www.topografix.com/GPX/1/1">
                  <trk>
                    <name>Ridge</name>
                    <trkseg>
                      <trkpt lat="47.45" lon="11.03"><ele>1200.4</ele><time>2026-05-03T08:00:00Z</time></trkpt>
                      <trkpt lat="47.46" lon="11.04"><ele>1240.9</ele><time>2026-05-03T08:10:00Z</time></trkpt>
                      <trkpt lat="47.47" lon="11.05"><ele>1210.2</ele><time>2026-05-03T08:20:00Z</time></trkpt>
                    </trkseg>
                  </trk>
                </gpx>
                """.trimIndent(),
            )

            val parsed = parser.parse(file)

            assertThat(parsed.pointCount).isEqualTo(3)
            assertThat(parsed.startedAt).isEqualTo(Instant.parse("2026-05-03T08:00:00Z"))
            assertThat(parsed.endedAt).isEqualTo(Instant.parse("2026-05-03T08:20:00Z"))
            assertThat(parsed.minLatitude).isEqualTo(47.45)
            assertThat(parsed.maxLongitude).isEqualTo(11.05)
            assertThat(parsed.distanceMeters).isGreaterThan(0)
            assertThat(parsed.ascentMeters).isEqualTo(41)
            assertThat(parsed.descentMeters).isEqualTo(31)
            assertThat(parsed.geometryJson).contains("\"LineString\"")
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `keeps elevation metadata empty when gpx has no elevation`() {
        val file = Files.createTempFile("veyq-track-", ".gpx")
        try {
            Files.writeString(
                file,
                """
                <gpx version="1.1" creator="veyq-test">
                  <trk>
                    <trkseg>
                      <trkpt lat="47.45" lon="11.03" />
                      <trkpt lat="47.46" lon="11.04" />
                    </trkseg>
                  </trk>
                </gpx>
                """.trimIndent(),
            )

            val parsed = parser.parse(file)

            assertThat(parsed.ascentMeters).isNull()
            assertThat(parsed.descentMeters).isNull()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `ignores non-finite and implausibly large elevation values`() {
        val file = Files.createTempFile("veyq-track-", ".gpx")
        try {
            // 1e309 overflows a Double to Infinity and previously rounded to
            // Long.MAX_VALUE, which could overflow Int64 aggregates on clients.
            Files.writeString(
                file,
                """
                <gpx version="1.1" creator="veyq-test">
                  <trk>
                    <trkseg>
                      <trkpt lat="47.45" lon="11.03"><ele>1e309</ele></trkpt>
                      <trkpt lat="47.46" lon="11.04"><ele>500000000</ele></trkpt>
                    </trkseg>
                  </trk>
                </gpx>
                """.trimIndent(),
            )

            val parsed = parser.parse(file)

            // Both elevations are dropped, so no ascent/descent is derived.
            assertThat(parsed.ascentMeters).isNull()
            assertThat(parsed.descentMeters).isNull()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `rejects gpx without enough points`() {
        val file = Files.createTempFile("veyq-track-", ".gpx")
        try {
            Files.writeString(
                file,
                """
                <gpx version="1.1" creator="veyq-test">
                  <trk><trkseg><trkpt lat="47.45" lon="11.03" /></trkseg></trk>
                </gpx>
                """.trimIndent(),
            )

            assertThatThrownBy { parser.parse(file) }
                .isInstanceOf(BadRequestException::class.java)
                .hasMessageContaining("at least two points")
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
