import SwiftUI
import MapKit

/// A memory's map: a MapKit map with a marker per geotagged medium. Tapping a marker opens that medium.
/// Coordinates come from the decrypted sealedMeta (the server is blind to them).
struct MemoryMapView: View {
    struct Point: Identifiable { let id: String; let lat: Double; let lng: Double }
    let points: [Point]
    var onSelect: (String) -> Void = { _ in }

    var body: some View {
        Map(initialPosition: .region(region)) {
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
        let lats = points.map(\.lat), lngs = points.map(\.lng)
        guard let minLat = lats.min(), let maxLat = lats.max(), let minLng = lngs.min(), let maxLng = lngs.max() else {
            return MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: 20, longitude: 0), span: MKCoordinateSpan(latitudeDelta: 120, longitudeDelta: 120))
        }
        let center = CLLocationCoordinate2D(latitude: (minLat + maxLat) / 2, longitude: (minLng + maxLng) / 2)
        let span = MKCoordinateSpan(latitudeDelta: max(0.02, (maxLat - minLat) * 1.4), longitudeDelta: max(0.02, (maxLng - minLng) * 1.4))
        return MKCoordinateRegion(center: center, span: span)
    }
}
