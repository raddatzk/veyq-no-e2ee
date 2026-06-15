// Parse a GPX file's XML into a simple {name, points} route for sealing + map rendering. We only need the
// track point coordinates (trkpt, or rtept for route files), so this ignores elevation/time and the rest of
// the GPX schema. The name comes from the track/metadata <name>, falling back to a generic label.

export type GpxTrack = { name: string; points: { lat: number; lng: number }[] }

/** Parse GPX text → {name, points}. Throws on malformed XML or a track with no usable points. */
export function parseGpx(xml: string): GpxTrack {
  const doc = new DOMParser().parseFromString(xml, 'application/xml')
  if (doc.querySelector('parsererror')) throw new Error('invalid GPX')
  const points = Array.from(doc.querySelectorAll('trkpt, rtept'))
    .map((p) => ({ lat: Number(p.getAttribute('lat')), lng: Number(p.getAttribute('lon')) }))
    .filter((p) => Number.isFinite(p.lat) && Number.isFinite(p.lng))
  if (!points.length) throw new Error('GPX has no track points')
  const name =
    doc.querySelector('trk > name')?.textContent?.trim() ||
    doc.querySelector('metadata > name')?.textContent?.trim() ||
    'Track'
  return { name, points }
}
