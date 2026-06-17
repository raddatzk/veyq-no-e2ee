import SwiftUI
import MapKit
import VeyqCrypto

/// A memory's map: a MapKit map with a marker per geotagged medium and a polyline per GPX track. Tapping a
/// marker opens that medium. Coordinates come from decrypted data (the server is blind to them).
struct MemoryMapView: View {
    struct Point: Identifiable { let id: String; let lat: Double; let lng: Double }
    let points: [Point]
    var tracks: [Track.TrackItem] = []
    var onSelect: (String) -> Void = { _ in }
    // The map (Apple Maps) reveals the decrypted coordinates to a third party, so it loads only after consent.
    // Stored locally (per device), revocable in Settings — never synced to the operator-blind server.
    @AppStorage("veyq.privacy.mapTiles") private var mapConsent = false

    var body: some View {
        if mapConsent { mapBody } else { consentPlaceholder }
    }

    private var consentPlaceholder: some View {
        VStack(spacing: 16) {
            Text(T("map.consentText")).foregroundStyle(VeyqStyle.muted).multilineTextAlignment(.center)
            Button(T("map.consentEnable")) { mapConsent = true }.buttonStyle(.borderedProminent)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 420)
        .padding()
        .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radius).strokeBorder(VeyqStyle.line, style: StrokeStyle(lineWidth: 1, dash: [6])))
    }

    // A small palette so multiple tracks are visually distinguishable (mirrors web TRACK_COLORS).
    static let trackColors: [Color] = [
        Color(red: 0.61, green: 0.55, blue: 1.0),   // accent violet
        Color(red: 0.20, green: 0.78, blue: 0.60),  // teal-green
        Color(red: 0.97, green: 0.58, blue: 0.27),  // orange
        Color(red: 0.40, green: 0.67, blue: 0.98),  // blue
        Color(red: 0.94, green: 0.45, blue: 0.64),  // pink
        Color(red: 0.85, green: 0.78, blue: 0.27),  // amber
    ]
    private func trackColor(_ i: Int) -> Color { Self.trackColors[i % Self.trackColors.count] }

    @State private var camera: MapCameraPosition = .automatic
    @State private var mapRect: MKMapRect?
    @State private var viewport: CGSize = .zero

    private var mapBody: some View {
        GeometryReader { geo in
            Map(position: $camera) {
                ForEach(Array(tracks.enumerated()), id: \.element.id) { idx, t in
                    let color = trackColor(idx)
                    MapPolyline(coordinates: t.points.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lng) })
                        .stroke(color, lineWidth: 4)
                    // Start (green) and end (red) markers per track, like the web map.
                    if let first = t.points.first {
                        Annotation("", coordinate: CLLocationCoordinate2D(latitude: first.lat, longitude: first.lng)) {
                            Circle().fill(.green).overlay(Circle().strokeBorder(.white, lineWidth: 2)).frame(width: 12, height: 12)
                        }
                    }
                    if let last = t.points.last, t.points.count > 1 {
                        Annotation("", coordinate: CLLocationCoordinate2D(latitude: last.lat, longitude: last.lng)) {
                            Circle().fill(.red).overlay(Circle().strokeBorder(.white, lineWidth: 2)).frame(width: 12, height: 12)
                        }
                    }
                }
                // Media markers, grouped into clusters by on-screen proximity at the current zoom (Legacy parity).
                ForEach(MapCluster.clusters(points, mapRect: mapRect, viewport: viewport)) { cluster in
                    Annotation("", coordinate: cluster.coordinate) {
                        if cluster.ids.count == 1 {
                            Circle().fill(VeyqStyle.accent).overlay(Circle().strokeBorder(.white, lineWidth: 2))
                                .frame(width: 16, height: 16)
                                .onTapGesture { onSelect(cluster.ids[0]) }
                        } else {
                            Text("\(cluster.ids.count)")
                                .font(.caption.bold()).foregroundStyle(.white)
                                .frame(width: 30, height: 30)
                                .background(VeyqStyle.accent, in: Circle())
                                .overlay(Circle().strokeBorder(.white, lineWidth: 2))
                                .onTapGesture { zoomInto(cluster) }
                        }
                    }
                }
            }
            .onMapCameraChange(frequency: .onEnd) { ctx in mapRect = ctx.rect }
            .onAppear { viewport = geo.size; camera = .region(region) }
        }
        .frame(height: 420)
        .clipShape(RoundedRectangle(cornerRadius: VeyqStyle.radius))
        .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radius).strokeBorder(VeyqStyle.line))
    }

    /// Zoom one step deeper, centred on a tapped cluster, so it splits into its members.
    private func zoomInto(_ cluster: MapCluster) {
        let span = mapRect.map { rect -> MKCoordinateSpan in
            let r = MKCoordinateRegion(rect)
            return MKCoordinateSpan(latitudeDelta: max(0.002, r.span.latitudeDelta / 3), longitudeDelta: max(0.002, r.span.longitudeDelta / 3))
        } ?? MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)
        withAnimation { camera = .region(MKCoordinateRegion(center: cluster.coordinate, span: span)) }
    }

    private var region: MKCoordinateRegion {
        let coords = points.map { ($0.lat, $0.lng) } + tracks.flatMap { $0.points.map { ($0.lat, $0.lng) } }
        let lats = coords.map(\.0), lngs = coords.map(\.1)
        guard let minLat = lats.min(), let maxLat = lats.max(), let minLng = lngs.min(), let maxLng = lngs.max() else {
            return MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: 20, longitude: 0), span: MKCoordinateSpan(latitudeDelta: 120, longitudeDelta: 120))
        }
        let center = CLLocationCoordinate2D(latitude: (minLat + maxLat) / 2, longitude: (minLng + maxLng) / 2)
        let span = MKCoordinateSpan(latitudeDelta: max(0.02, (maxLat - minLat) * 1.4), longitudeDelta: max(0.02, (maxLng - minLng) * 1.4))
        return MKCoordinateRegion(center: center, span: span)
    }
}

/// A group of media markers collapsed into one pin. Grouping is by metric distance, where the threshold is
/// derived from the current visible map rect + viewport so clusters split apart as you zoom in (Legacy parity).
struct MapCluster: Identifiable {
    let id: String
    let coordinate: CLLocationCoordinate2D
    let ids: [String]

    static func clusters(_ points: [MemoryMapView.Point], mapRect: MKMapRect?, viewport: CGSize) -> [MapCluster] {
        let threshold = maxDistanceMeters(mapRect: mapRect, viewport: viewport)
        var acc: [(coord: CLLocationCoordinate2D, ids: [String])] = []
        // deterministic order so the same input yields stable cluster ids (avoids flicker on re-render)
        for p in points.sorted(by: { $0.id < $1.id }) {
            let loc = CLLocation(latitude: p.lat, longitude: p.lng)
            if let i = acc.firstIndex(where: { CLLocation(latitude: $0.coord.latitude, longitude: $0.coord.longitude).distance(from: loc) <= threshold }) {
                acc[i].ids.append(p.id)
                let lat = (acc[i].coord.latitude * Double(acc[i].ids.count - 1) + p.lat) / Double(acc[i].ids.count)
                let lng = (acc[i].coord.longitude * Double(acc[i].ids.count - 1) + p.lng) / Double(acc[i].ids.count)
                acc[i].coord = CLLocationCoordinate2D(latitude: lat, longitude: lng)
            } else {
                acc.append((CLLocationCoordinate2D(latitude: p.lat, longitude: p.lng), [p.id]))
            }
        }
        return acc.map { MapCluster(id: $0.ids.joined(separator: ":"), coordinate: $0.coord, ids: $0.ids) }
    }

    private static func maxDistanceMeters(mapRect: MKMapRect?, viewport: CGSize) -> CLLocationDistance {
        let markerDimension = 44.0
        guard let mapRect, viewport.width > 0, viewport.height > 0 else { return 50_000 }
        let centerLat = MKMapPoint(x: mapRect.midX, y: mapRect.midY).coordinate.latitude
        let metersPerMapPoint = MKMetersPerMapPointAtLatitude(centerLat)
        let visibleMeters = max(mapRect.width, mapRect.height) * metersPerMapPoint
        let metersPerScreenPoint = visibleMeters / Double(max(viewport.width, viewport.height))
        return markerDimension * 2 * metersPerScreenPoint
    }
}
