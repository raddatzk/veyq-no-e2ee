package app.veyq.android.ui

import app.veyq.crypto.Track
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parse a GPX file's XML into a {name, points} route for sealing + map rendering. Only track-point
 * coordinates (trkpt, or rtept for route files) are needed; elevation/time are ignored. Name comes from the
 * track/metadata <name>, falling back to a generic label. Returns null on malformed XML or no usable points.
 */
object Gpx {
    fun parse(data: ByteArray): Pair<String, List<Track.Point>>? = runCatching {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(data))
        var points = collect(doc.getElementsByTagName("trkpt"))
        if (points.isEmpty()) points = collect(doc.getElementsByTagName("rtept"))
        if (points.isEmpty()) return@runCatching null
        val name = childName(doc, "trk") ?: childName(doc, "metadata") ?: "Track"
        name to points
    }.getOrNull()

    private fun collect(nodes: org.w3c.dom.NodeList): List<Track.Point> =
        (0 until nodes.length).mapNotNull { i ->
            val e = nodes.item(i) as? Element ?: return@mapNotNull null
            val lat = e.getAttribute("lat").toDoubleOrNull()
            val lon = e.getAttribute("lon").toDoubleOrNull()
            if (lat != null && lon != null) Track.Point(lat, lon) else null
        }

    /** The <name> text directly under the first <parent> element, trimmed; null if absent/blank. */
    private fun childName(doc: org.w3c.dom.Document, parent: String): String? {
        val p = doc.getElementsByTagName(parent).item(0) as? Element ?: return null
        val children = p.childNodes
        for (i in 0 until children.length) {
            val c = children.item(i) as? Element ?: continue
            if (c.tagName == "name") return c.textContent?.trim()?.ifEmpty { null }
        }
        return null
    }
}
