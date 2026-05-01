export type MediaGridLayoutInput = {
  ids: string[]
  aspectRatios: Record<string, number | undefined>
  containerWidth: number
  targetHeight: number
  gap: number
}

export type MediaGridLayoutItem = {
  id: string
  width: number
  height: number
}

export type MediaGridLayoutRow = {
  items: MediaGridLayoutItem[]
  width: number
  height: number
}

const fallbackAspectRatio = 4 / 3
const minHeightRatio = 0.65
const maxHeightRatio = 1.6

export function buildJustifiedMediaRows(input: MediaGridLayoutInput): MediaGridLayoutRow[] {
  const containerWidth = Math.max(0, input.containerWidth)
  if (!input.ids.length || containerWidth <= 0 || input.targetHeight <= 0) return []

  const rows: MediaGridLayoutRow[] = []
  let currentIds: string[] = []
  let currentAspectRatioSum = 0

  input.ids.forEach((id) => {
    const aspectRatio = safeAspectRatio(input.aspectRatios[id])
    currentIds.push(id)
    currentAspectRatioSum += aspectRatio

    const rowGapWidth = input.gap * Math.max(0, currentIds.length - 1)
    const targetWidth = currentAspectRatioSum * input.targetHeight + rowGapWidth
    if (targetWidth >= containerWidth) {
      rows.push(buildRow(currentIds, input, containerWidth))
      currentIds = []
      currentAspectRatioSum = 0
    }
  })

  if (currentIds.length) {
    rows.push(buildRow(currentIds, input, containerWidth))
  }

  return rows
}

function buildRow(
  ids: string[],
  input: MediaGridLayoutInput,
  containerWidth: number
): MediaGridLayoutRow {
  const gapWidth = input.gap * Math.max(0, ids.length - 1)
  const availableWidth = Math.max(0, containerWidth - gapWidth)
  const aspectRatioSum = ids.reduce(
    (sum, id) => sum + safeAspectRatio(input.aspectRatios[id]),
    0
  )
  const rawHeight = availableWidth / aspectRatioSum
  const minHeight = input.targetHeight * minHeightRatio
  const maxHeight = input.targetHeight * maxHeightRatio
  const height = clamp(rawHeight, minHeight, maxHeight)
  const items = ids.map((id) => ({
    id,
    width: Math.min(containerWidth, safeAspectRatio(input.aspectRatios[id]) * height),
    height
  }))

  return {
    items,
    width: items.reduce((sum, item) => sum + item.width, gapWidth),
    height
  }
}

function safeAspectRatio(value: number | undefined): number {
  return value && Number.isFinite(value) && value > 0 ? value : fallbackAspectRatio
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}
