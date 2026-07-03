// The cover image for a space-map pin (cr-0071 follow-up). A located Memory/Chapter shows the photo taken there
// as a round thumbnail pin (like the legacy media map) instead of a bare marker. Operator-blind: it reuses the
// gallery's decrypt path — `listMedia` (opaque metadata + K_blob wraps) then `openGrid` (the small THUMBNAIL
// tier, fetched as ciphertext + decrypted on device under `K_mem`). No new endpoint, no plaintext crosses.
import type { LatLng } from './memoryLocation'
import { hasDerivatives, listMedia, openGrid, unsealMeta, type MediaItem } from './media'

/**
 * Pick which media represents a pin: the derivable item whose GPS is **nearest** the pin position `near` (the
 * photo actually taken at that spot), else — when no item is geotagged, or `near` is absent — the **first**
 * derivable item. Null when the node has no derivable media (nothing to show ⇒ the caller keeps a plain pin).
 * Pure (metadata only, no fetch) so the selection is unit-tested; distance is squared-degrees (fine for argmin).
 */
export function pickCoverMedia(items: MediaItem[], nodeKey: Uint8Array, near?: LatLng): MediaItem | null {
  const derivable = items.filter(hasDerivatives)
  if (derivable.length === 0) return null
  if (!near) return derivable[0]
  let best: MediaItem | null = null
  let bestDist = Infinity
  for (const item of derivable) {
    const meta = unsealMeta(nodeKey, item)
    if (meta?.gpsLat === undefined || meta.gpsLng === undefined) continue
    const dist = (meta.gpsLat - near.lat) ** 2 + (meta.gpsLng - near.lng) ** 2
    if (dist < bestDist) {
      bestDist = dist
      best = item
    }
  }
  return best ?? derivable[0] // no geotagged media ⇒ just the first derivable one
}

/**
 * Load a decrypted cover thumbnail (an object URL) for a node's pin, or **null** on no derivable media / any
 * failure — the caller then falls back to a plain pin. The caller owns the URL and must `revokeObjectURL` it.
 * Best-effort by design (a broken tile never breaks the map).
 */
export async function loadPinCover(
  scopeId: string,
  nodeKey: Uint8Array,
  nodeId: string,
  near?: LatLng,
): Promise<string | null> {
  try {
    const media = await listMedia(scopeId, nodeId)
    const pick = pickCoverMedia(media, nodeKey, near)
    if (!pick) return null
    const bytes = await openGrid(scopeId, nodeKey, pick)
    return URL.createObjectURL(new Blob([bytes as BlobPart]))
  } catch {
    return null // best-effort: locked blob, network hiccup, malformed tier ⇒ plain pin
  }
}
