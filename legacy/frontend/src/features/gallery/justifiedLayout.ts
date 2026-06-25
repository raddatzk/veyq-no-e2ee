// Justified gallery layout (Flickr/Photos style): greedily fill a row until its summed aspect ratio
// reaches the container's, then justify that row to the full width (so every full row has equal height and
// flush edges); the trailing incomplete row is a "widow" kept at the target height. Pure + deterministic —
// ported from the reference (legacy mediaGridLayout.ts) so the layout matches exactly.
export type LayoutSource = { id: string; aspectRatio?: number }
export type LayoutInput = { items: LayoutSource[]; containerWidth: number; targetHeight: number; gap: number; showWidows?: boolean }
export type LayoutTile = { id: string; left: number; top: number; renderedWidth: number; renderedHeight: number; widow: boolean }
export type LayoutResult = { tiles: LayoutTile[]; height: number }

const FALLBACK_AR = 4 / 3
const MIN_AR = 3 / 4
const MAX_AR = 4 / 3

function safeAspectRatio(value: number | undefined): number {
  return value && Number.isFinite(value) && value > 0 ? value : FALLBACK_AR
}

/** Clamp a raw width/height ratio into the gallery's allowed band so extremes don't dominate a row. */
export function galleryDisplayAspectRatio(width?: number | null, height?: number | null): number {
  if (!width || !height || width <= 0 || height <= 0) return FALLBACK_AR
  const ar = width / height
  if (!Number.isFinite(ar) || ar <= 0) return FALLBACK_AR
  return Math.min(MAX_AR, Math.max(MIN_AR, ar))
}

function layoutRow(row: Required<LayoutSource>[], top: number, containerWidth: number, targetHeight: number, gap: number, forceJustify: boolean): { tiles: LayoutTile[]; height: number } {
  const rowGapTotal = gap * Math.max(row.length - 1, 0)
  const availableWidth = Math.max(containerWidth - rowGapTotal, 0)
  const aspectRatioSum = row.reduce((sum, item) => sum + item.aspectRatio, 0)
  const height = forceJustify && aspectRatioSum > 0 ? availableWidth / aspectRatioSum : targetHeight

  let left = 0
  const tiles = row.map((item, index) => {
    const isLast = index === row.length - 1
    const rawWidth = item.aspectRatio * height
    const renderedWidth = forceJustify && isLast ? Math.max(containerWidth - left, 0) : rawWidth
    const tile: LayoutTile = { id: item.id, left, top, renderedWidth, renderedHeight: height, widow: !forceJustify }
    left += renderedWidth + gap
    return tile
  })
  return { tiles, height }
}

export function calculateJustifiedLayout(input: LayoutInput): LayoutResult {
  const containerWidth = Math.max(0, Math.floor(input.containerWidth))
  const targetHeight = Math.max(0, input.targetHeight)
  const gap = Math.max(0, input.gap)
  const showWidows = input.showWidows ?? true
  const items = input.items.map((item) => ({ id: item.id, aspectRatio: safeAspectRatio(item.aspectRatio) }))

  if (items.length === 0 || containerWidth <= 0 || targetHeight <= 0) return { tiles: [], height: 0 }

  const targetAspectRatio = containerWidth / targetHeight
  const tiles: LayoutTile[] = []
  let row: Required<LayoutSource>[] = []
  let rowAspectRatio = 0
  let top = 0

  for (const item of items) {
    row.push(item)
    rowAspectRatio += item.aspectRatio
    if (rowAspectRatio >= targetAspectRatio) {
      const laidOut = layoutRow(row, top, containerWidth, targetHeight, gap, true)
      tiles.push(...laidOut.tiles)
      top += laidOut.height + gap
      row = []
      rowAspectRatio = 0
    }
  }

  if (row.length > 0 && showWidows) {
    const laidOut = layoutRow(row, top, containerWidth, targetHeight, gap, false)
    tiles.push(...laidOut.tiles)
    top += laidOut.height
  } else if (row.length === 0 && tiles.length > 0) {
    top -= gap
  }

  return { tiles, height: Math.max(top, 0) }
}
