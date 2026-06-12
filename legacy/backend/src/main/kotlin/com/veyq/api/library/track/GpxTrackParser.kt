package com.veyq.api.library.track

import com.fasterxml.jackson.databind.ObjectMapper
import com.veyq.api.auth.BadRequestException
import jakarta.inject.Singleton
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.*

data class ParsedTrack(
    val distanceMeters: Long,
    val ascentMeters: Long?,
    val descentMeters: Long?,
    val pointCount: Int,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val minLatitude: Double,
    val minLongitude: Double,
    val maxLatitude: Double,
    val maxLongitude: Double,
    val geometryJson: String,
)

@Singleton
class GpxTrackParser(
    private val objectMapper: ObjectMapper,
) {
    fun parse(path: Path): ParsedTrack {
        if (Files.size(path) > MAX_GPX_BYTES) {
            throw BadRequestException("GPX file is too large", "MEMORY_TRACK_GPX_TOO_LARGE")
        }
        val document = try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.newDocumentBuilder().parse(path.toFile())
        } catch (e: Exception) {
            throw BadRequestException("Invalid GPX file", "MEMORY_TRACK_GPX_INVALID")
        }
        val root = document.documentElement
        if (root == null || root.localName != "gpx") {
            throw BadRequestException("Invalid GPX file", "MEMORY_TRACK_GPX_INVALID")
        }

        val points = elementsByLocalName(root, "trkpt").ifEmpty { elementsByLocalName(root, "rtept") }
            .map { pointElement ->
                val lat = pointElement.getAttribute("lat").toDoubleOrNull()
                val lon = pointElement.getAttribute("lon").toDoubleOrNull()
                if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                    throw BadRequestException("Invalid GPX coordinates", "MEMORY_TRACK_GPX_INVALID")
                }
                TrackPoint(lat, lon, pointElevation(pointElement), pointTime(pointElement))
            }

        if (points.size < 2) {
            throw BadRequestException("GPX track must contain at least two points", "MEMORY_TRACK_GPX_TOO_SHORT")
        }
        if (points.size > MAX_POINTS) {
            throw BadRequestException("GPX track contains too many points", "MEMORY_TRACK_GPX_TOO_LARGE")
        }

        val sampled = sampleForGeometry(points)
        val geometry = mapOf(
            "type" to "LineString",
            "coordinates" to sampled.map { listOf(roundCoordinate(it.longitude), roundCoordinate(it.latitude)) },
        )

        return ParsedTrack(
            distanceMeters = distanceMeters(points),
            ascentMeters = ascentMeters(points),
            descentMeters = descentMeters(points),
            pointCount = points.size,
            startedAt = points.firstNotNullOfOrNull { it.time },
            endedAt = points.asReversed().firstNotNullOfOrNull { it.time },
            minLatitude = points.minOf { it.latitude },
            minLongitude = points.minOf { it.longitude },
            maxLatitude = points.maxOf { it.latitude },
            maxLongitude = points.maxOf { it.longitude },
            geometryJson = objectMapper.writeValueAsString(geometry),
        )
    }

    private fun elementsByLocalName(root: Element, localName: String): List<Element> {
        val nodes = root.getElementsByTagNameNS("*", localName)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun pointTime(point: Element): Instant? {
        val nodes = point.getElementsByTagNameNS("*", "time")
        val value = (0 until nodes.length)
            .mapNotNull { nodes.item(it)?.textContent?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?: return null
        return runCatching { Instant.parse(value) }.getOrNull()
    }

    private fun pointElevation(point: Element): Double? {
        val nodes = point.getElementsByTagNameNS("*", "ele")
        return (0 until nodes.length)
            .mapNotNull { nodes.item(it)?.textContent?.trim()?.toDoubleOrNull() }
            .firstOrNull { it.isFinite() && abs(it) <= MAX_ELEVATION_METERS }
    }

    private fun distanceMeters(points: List<TrackPoint>): Long =
        points.zipWithNext().sumOf { (a, b) -> haversineMeters(a, b) }.roundToLong()

    private fun ascentMeters(points: List<TrackPoint>): Long? =
        elevationDeltaMeters(points, rising = true)

    private fun descentMeters(points: List<TrackPoint>): Long? =
        elevationDeltaMeters(points, rising = false)

    private fun elevationDeltaMeters(points: List<TrackPoint>, rising: Boolean): Long? {
        if (points.none { it.elevationMeters != null }) return null
        return points.zipWithNext().sumOf { (a, b) ->
            val from = a.elevationMeters
            val to = b.elevationMeters
            if (from == null || to == null) {
                0.0
            } else {
                val delta = to - from
                when {
                    rising && delta > 0 -> delta
                    !rising && delta < 0 -> -delta
                    else -> 0.0
                }
            }
        }.roundToLong()
    }

    private fun sampleForGeometry(points: List<TrackPoint>): List<TrackPoint> {
        if (points.size <= MAX_GEOMETRY_POINTS) return points
        val lastIndex = points.lastIndex
        return (0 until MAX_GEOMETRY_POINTS).map { index ->
            points[((index.toDouble() / (MAX_GEOMETRY_POINTS - 1)) * lastIndex).roundToInt()]
        }.distinct()
    }

    private fun haversineMeters(a: TrackPoint, b: TrackPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private fun roundCoordinate(value: Double): Double =
        (value * 1_000_000.0).roundToLong() / 1_000_000.0

    private data class TrackPoint(
        val latitude: Double,
        val longitude: Double,
        val elevationMeters: Double?,
        val time: Instant?,
    )

    companion object {
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val MAX_GPX_BYTES = 25L * 1024L * 1024L
        private const val MAX_POINTS = 50_000
        private const val MAX_GEOMETRY_POINTS = 2_000

        // Clamp elevations to a physically plausible range so that crafted GPX
        // files cannot persist absurd (or overflowing) ascent/descent metrics.
        // Comfortably beyond Mount Everest (8849 m) and the Mariana Trench.
        private const val MAX_ELEVATION_METERS = 100_000.0
    }
}
