// Parse a GPX file's XML into a {name, points} route for sealing + map rendering + track stats. Each track
// point (trkpt, or rtept for route files) carries its coordinates plus optional elevation (<ele>, metres) and
// timestamp (<time>, ISO 8601) when present — these drive ascent/descent and duration stats. The name comes
// from the track/metadata <name>, falling back to a generic label. The whole route is sealed client-side.

export type GpxPoint = { lat: number; lng: number; ele?: number; time?: string }
export type GpxTrack = { name: string; points: GpxPoint[] }

/** Parse GPX text → {name, points}. Throws on malformed XML or a track with no usable points. */
export function parseGpx(xml: string): GpxTrack {
  const doc = new DOMParser().parseFromString(xml, 'application/xml')
  if (doc.querySelector('parsererror')) throw new Error('invalid GPX')
  const points = Array.from(doc.querySelectorAll('trkpt, rtept'))
    .map((p) => {
      const ele = Number(p.querySelector('ele')?.textContent)
      const time = p.querySelector('time')?.textContent?.trim() || undefined
      return { lat: Number(p.getAttribute('lat')), lng: Number(p.getAttribute('lon')), ele: Number.isFinite(ele) ? ele : undefined, time }
    })
    .filter((p) => Number.isFinite(p.lat) && Number.isFinite(p.lng))
  if (!points.length) throw new Error('GPX has no track points')
  const name =
    doc.querySelector('trk > name')?.textContent?.trim() ||
    doc.querySelector('metadata > name')?.textContent?.trim() ||
    'Track'
  return { name, points }
}
