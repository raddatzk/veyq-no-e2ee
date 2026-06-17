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

    private var mapBody: some View {
        Map(initialPosition: .region(region)) {
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
            ForEach(points) { p in
                Annotation("", coordinate: CLLocationCoordinate2D(latitude: p.lat, longitude: p.lng)) {
                    Circle().fill(VeyqStyle.accent).overlay(Circle().strokeBorder(.white, lineWidth: 2))
                        .frame(width: 16, height: 16)
                        .onTapGesture { onSelect(p.id) }
                }
            }
        }
        .frame(height: 420)
        .clipShape(RoundedRectangle(cornerRadius: VeyqStyle.radius))
        .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radius).strokeBorder(VeyqStyle.line))
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
