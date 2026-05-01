import { flickrJustifiedLayout } from './vendor/flickrJustifiedLayout'

export type MediaGridLayoutInput = {
  ids: string[]
  aspectRatios: Record<string, number | undefined>
  containerWidth: number
  targetHeight: number
  minHeight?: number
  maxHeight?: number
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
  align: 'justify' | 'center'
  widow: boolean
}

const fallbackAspectRatio = 4 / 3
const rowTopTolerance = 0.5

export function buildJustifiedMediaRows(input: MediaGridLayoutInput): MediaGridLayoutRow[] {
  const normalized = normalizeInput(input)
  if (!normalized.ids.length || normalized.containerWidth <= 0 || normalized.targetHeight <= 0) {
    return []
  }

  const geometry = flickrJustifiedLayout(
    normalized.ids.map((id) => safeAspectRatio(normalized.aspectRatios[id])),
    {
      containerWidth: normalized.containerWidth,
      boxSpacing: normalized.gap,
      targetRowHeight: normalized.targetHeight,
      targetRowHeightTolerance: targetRowHeightTolerance(normalized),
      minRowHeight: normalized.minHeight,
      maxRowHeight: normalized.maxHeight,
      showWidows: true,
      widowLayoutStyle: 'center'
    }
  )

  return groupBoxesIntoRows(normalized.ids, geometry.boxes, normalized.gap, geometry.widowCount)
}

export function targetRowHeightTolerance(
  input: Pick<Required<MediaGridLayoutInput>, 'targetHeight' | 'minHeight' | 'maxHeight'>
): number {
  if (input.targetHeight <= 0) return 0
  return (
    Math.max(input.targetHeight - input.minHeight, input.maxHeight - input.targetHeight, 0) /
    input.targetHeight
  )
}

function normalizeInput(input: MediaGridLayoutInput): Required<MediaGridLayoutInput> {
  return {
    ...input,
    containerWidth: Math.max(0, input.containerWidth),
    minHeight: input.minHeight ?? input.targetHeight * 0.8,
    maxHeight: input.maxHeight ?? input.targetHeight
  }
}

function groupBoxesIntoRows(
  ids: string[],
  boxes: ReturnType<typeof flickrJustifiedLayout>['boxes'],
  gap: number,
  widowCount: number
): MediaGridLayoutRow[] {
  const rows: MediaGridLayoutRow[] = []
  let currentTop: number | null = null
  const widowStartIndex = boxes.length - widowCount

  boxes.forEach((box, index) => {
    const isWidow = index >= widowStartIndex

    if (currentTop === null || Math.abs(box.top - currentTop) > rowTopTolerance) {
      currentTop = box.top
      rows.push({
        items: [],
        width: 0,
        height: box.height,
        align: box.layoutStyle === 'center' ? 'center' : 'justify',
        widow: isWidow
      })
    }

    const row = rows[rows.length - 1]
    row.items.push({
      id: ids[index],
      width: box.width,
      height: box.height
    })
    row.height = box.height
    row.align = box.layoutStyle === 'center' ? 'center' : row.align
    row.widow = row.widow || isWidow
    row.width = row.items.reduce((sum, item) => sum + item.width, gap * Math.max(0, row.items.length - 1))
  })

  return rows
}

function safeAspectRatio(value: number | undefined): number {
  return value && Number.isFinite(value) && value > 0 ? value : fallbackAspectRatio
}
