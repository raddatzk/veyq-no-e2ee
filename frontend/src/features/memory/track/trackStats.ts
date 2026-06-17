// Track statistics derived from a route's points: distance always; ascent/descent when elevations are present;
// duration when timestamps are present (GPX <ele>/<time>, kept by the parser). Missing fields → that stat is
// omitted, never wrong. Mirrored on iOS/Android.

export type TrackPoint = { lat: number; lng: number; ele?: number; time?: string }
export type TrackStats = { distanceM: number; ascentM?: number; descentM?: number; durationS?: number }

const EARTH_RADIUS_M = 6_371_000

/** Great-circle distance between two points (haversine), in metres. */
function haversine(a: TrackPoint, b: TrackPoint): number {
  const toRad = (d: number) => (d * Math.PI) / 180
  const dLat = toRad(b.lat - a.lat)
  const dLng = toRad(b.lng - a.lng)
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLng / 2) ** 2
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)))
}

export function trackStats(points: TrackPoint[]): TrackStats {
  let distanceM = 0
  let ascentM = 0
  let descentM = 0
  let haveEle = false
  for (let i = 1; i < points.length; i++) {
    distanceM += haversine(points[i - 1], points[i])
    const a = points[i - 1].ele
    const b = points[i].ele
    if (a != null && b != null) {
      haveEle = true
      const d = b - a
      if (d > 0) ascentM += d
      else descentM -= d
    }
  }
  const times = points.map((p) => p.time).filter((t): t is string => !!t)
  const start = times.length ? Date.parse(times[0]) : NaN
  const end = times.length ? Date.parse(times[times.length - 1]) : NaN
  const durationS = Number.isFinite(start) && Number.isFinite(end) && end > start ? Math.round((end - start) / 1000) : undefined
  return { distanceM, ascentM: haveEle ? ascentM : undefined, descentM: haveEle ? descentM : undefined, durationS }
}

/** "850 m" under 1 km, otherwise "12.3 km". */
export function formatDistance(meters: number): string {
  return meters < 1000 ? `${Math.round(meters)} m` : `${(meters / 1000).toFixed(1)} km`
}

/** "1 h 23 min" / "23 min" / "45 s". */
export function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  if (h > 0) return `${h} h ${m} min`
  if (m > 0) return `${m} min`
  return `${seconds} s`
}

/** "↑120 m ↓95 m" (rounded), or empty when no elevation data. */
export function formatElevation(stats: TrackStats): string {
  if (stats.ascentM == null) return ''
  return `↑${Math.round(stats.ascentM)} m ↓${Math.round(stats.descentM ?? 0)} m`
}
