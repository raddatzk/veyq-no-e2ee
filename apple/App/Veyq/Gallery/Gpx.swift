import Foundation
import VeyqCrypto

/// Parse a GPX file's XML into a {name, points} route for sealing + map rendering. Each track-point (trkpt, or
/// rtept for route files) keeps its coordinates plus optional <ele> and <time> so track stats (distance always,
/// ascent/descent from elevation, duration from timestamps) can be derived. Name comes from the track/metadata
/// <name>, falling back to a generic label.
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
        private var capturingEle = false
        private var capturingTime = false
        private var inTrk = false
        private var inPoint = false
        private var buffer = ""
        // pending point being assembled
        private var ptLat = 0.0, ptLng = 0.0
        private var ptEle: Double?
        private var ptTime: String?

        func parser(_ p: XMLParser, didStartElement el: String, namespaceURI: String?, qualifiedName: String?, attributes a: [String: String]) {
            switch el {
            case "trk": inTrk = true
            case "trkpt", "rtept":
                if let la = a["lat"], let lo = a["lon"], let lat = Double(la), let lng = Double(lo) {
                    inPoint = true; ptLat = lat; ptLng = lng; ptEle = nil; ptTime = nil
                }
            case "ele" where inPoint: capturingEle = true; buffer = ""
            case "time" where inPoint: capturingTime = true; buffer = ""
            case "name": capturingName = true; buffer = ""
            default: break
            }
        }

        func parser(_ p: XMLParser, foundCharacters s: String) { if capturingName || capturingEle || capturingTime { buffer += s } }

        func parser(_ p: XMLParser, didEndElement el: String, namespaceURI: String?, qualifiedName: String?) {
            let trimmed = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
            switch el {
            case "name":
                if !trimmed.isEmpty && (name.isEmpty || inTrk) { name = trimmed } // prefer the <trk> name over <metadata>
                capturingName = false
            case "ele" where capturingEle:
                ptEle = Double(trimmed); capturingEle = false
            case "time" where capturingTime:
                if !trimmed.isEmpty { ptTime = trimmed }; capturingTime = false
            case "trkpt", "rtept":
                if inPoint { points.append(Track.Point(lat: ptLat, lng: ptLng, ele: ptEle, time: ptTime)); inPoint = false }
            case "trk": inTrk = false
            default: break
            }
        }
    }
}
