import Foundation
import VeyqCrypto

/// Parse a GPX file's XML into a {name, points} route for sealing + map rendering. Only track-point
/// coordinates (trkpt, or rtept for route files) are needed; elevation/time are ignored. Name comes from
/// the track/metadata <name>, falling back to a generic label.
enum Gpx {
    static func parse(_ data: Data) -> Track.Route? {
        let delegate = Delegate()
        let xml = XMLParser(data: data)
        xml.delegate = delegate
        guard xml.parse(), !delegate.points.isEmpty else { return nil }
        return Track.Route(name: delegate.name.isEmpty ? "Track" : delegate.name, points: delegate.points)
    }

    private final class Delegate: NSObject, XMLParserDelegate {
        var points: [Track.Point] = []
        var name = ""
        private var capturingName = false
        private var inTrk = false
        private var buffer = ""

        func parser(_ p: XMLParser, didStartElement el: String, namespaceURI: String?, qualifiedName: String?, attributes a: [String: String]) {
            switch el {
            case "trk": inTrk = true
            case "trkpt", "rtept":
                if let la = a["lat"], let lo = a["lon"], let lat = Double(la), let lng = Double(lo) { points.append(Track.Point(lat: lat, lng: lng)) }
            case "name": capturingName = true; buffer = ""
            default: break
            }
        }

        func parser(_ p: XMLParser, foundCharacters s: String) { if capturingName { buffer += s } }

        func parser(_ p: XMLParser, didEndElement el: String, namespaceURI: String?, qualifiedName: String?) {
            if el == "name" {
                let t = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
                if !t.isEmpty && (name.isEmpty || inTrk) { name = t } // prefer the <trk> name over <metadata>
                capturingName = false
            }
            if el == "trk" { inTrk = false }
        }
    }
}
