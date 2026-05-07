/*!
 * Adapted from Flickr's justified-layout.
 * Copyright 2019 SmugMug, Inc.
 * Licensed under the terms of the MIT license.
 *
 * Original project: https://github.com/flickr/justified-layout
 */

export type FlickrJustifiedLayoutConfig = {
  containerWidth: number
  boxSpacing: number
  targetRowHeight: number
  targetRowHeightTolerance: number
  minRowHeight: number
  maxRowHeight: number
  showWidows: boolean
  widowLayoutStyle: 'left' | 'justify' | 'center'
}

export type FlickrJustifiedLayoutBox = {
  aspectRatio: number
  top: number
  left: number
  width: number
  height: number
  layoutStyle: 'justify' | 'center' | 'left'
}

export type FlickrJustifiedLayoutGeometry = {
  containerHeight: number
  widowCount: number
  boxes: FlickrJustifiedLayoutBox[]
}

type LayoutItem = {
  aspectRatio: number
  top?: number
  left?: number
  width?: number
  height?: number
  layoutStyle?: 'justify' | 'center' | 'left'
}

class Row {
  readonly top: number
  readonly left: number
  readonly width: number
  readonly spacing: number
  readonly targetRowHeight: number
  readonly minAspectRatio: number
  readonly maxAspectRatio: number
  readonly minRowHeight: number
  readonly maxRowHeight: number
  readonly widowLayoutStyle: 'left' | 'justify' | 'center'
  readonly items: LayoutItem[] = []
  height = 0

  constructor(params: {
    top: number
    left: number
    width: number
    spacing: number
    targetRowHeight: number
    minRowHeight: number
    maxRowHeight: number
    widowLayoutStyle: 'left' | 'justify' | 'center'
  }) {
    this.top = params.top
    this.left = params.left
    this.width = params.width
    this.spacing = params.spacing
    this.targetRowHeight = params.targetRowHeight
    this.minRowHeight = params.minRowHeight
    this.maxRowHeight = params.maxRowHeight
    this.minAspectRatio = this.width / params.maxRowHeight
    this.maxAspectRatio = this.width / params.minRowHeight
    this.widowLayoutStyle = params.widowLayoutStyle
  }

  addItem(itemData: LayoutItem): boolean {
    const newItems = this.items.concat(itemData)
    const rowWidthWithoutSpacing = this.width - (newItems.length - 1) * this.spacing
    const newAspectRatio = sumAspectRatios(newItems)
    const targetAspectRatio = rowWidthWithoutSpacing / this.targetRowHeight

    if (newAspectRatio < this.minAspectRatio) {
      this.items.push({ ...itemData })
      return true
    }

    if (newAspectRatio > this.maxAspectRatio) {
      if (this.items.length === 0) {
        this.items.push({ ...itemData })
        this.completeLayout(rowWidthWithoutSpacing / newAspectRatio, 'justify')
        return true
      }

      const previousRowWidthWithoutSpacing = this.width - (this.items.length - 1) * this.spacing
      const previousAspectRatio = sumAspectRatios(this.items)
      const previousTargetAspectRatio = previousRowWidthWithoutSpacing / this.targetRowHeight

      if (
        Math.abs(newAspectRatio - targetAspectRatio) >
        Math.abs(previousAspectRatio - previousTargetAspectRatio)
      ) {
        this.completeLayout(previousRowWidthWithoutSpacing / previousAspectRatio, 'justify')
        return false
      }

      this.items.push({ ...itemData })
      this.completeLayout(rowWidthWithoutSpacing / newAspectRatio, 'justify')
      return true
    }

    this.items.push({ ...itemData })
    this.completeLayout(rowWidthWithoutSpacing / newAspectRatio, 'justify')
    return true
  }

  isLayoutComplete(): boolean {
    return this.height > 0
  }

  completeLayout(newHeight: number, layoutStyle: 'left' | 'justify' | 'center' = 'left') {
    const clampedHeight = Math.max(this.minRowHeight, Math.min(newHeight, this.maxRowHeight))
    const rowWidthWithoutSpacing = this.width - (this.items.length - 1) * this.spacing
    const ratioCorrection =
      newHeight === clampedHeight
        ? 1
        : rowWidthWithoutSpacing / clampedHeight / (rowWidthWithoutSpacing / newHeight)

    this.height = clampedHeight
    let itemWidthSum = this.left

    this.items.forEach((item) => {
      item.top = this.top
      item.width = item.aspectRatio * this.height * ratioCorrection
      item.height = this.height
      item.left = itemWidthSum
      item.layoutStyle = layoutStyle
      itemWidthSum += item.width + this.spacing
    })

    if (layoutStyle === 'justify') {
      this.justifyItemWidths(itemWidthSum)
    } else if (layoutStyle === 'center') {
      const centerOffset = (this.width - itemWidthSum) / 2
      this.items.forEach((item) => {
        item.left = (item.left || 0) + centerOffset + this.spacing
      })
    }
  }

  forceComplete(rowHeight?: number) {
    this.completeLayout(rowHeight ?? this.targetRowHeight, this.widowLayoutStyle)
  }

  getItems(): FlickrJustifiedLayoutBox[] {
    return this.items as FlickrJustifiedLayoutBox[]
  }

  private justifyItemWidths(itemWidthSum: number) {
    itemWidthSum -= this.spacing + this.left
    const errorWidthPerItem = (itemWidthSum - this.width) / this.items.length
    const roundedCumulativeErrors = this.items.map((_, index) =>
      Math.round((index + 1) * errorWidthPerItem)
    )

    if (this.items.length === 1) {
      this.items[0].width = (this.items[0].width || 0) - Math.round(errorWidthPerItem)
      return
    }

    this.items.forEach((item, index) => {
      if (index > 0) {
        item.left = (item.left || 0) - roundedCumulativeErrors[index - 1]
        item.width =
          (item.width || 0) -
          (roundedCumulativeErrors[index] - roundedCumulativeErrors[index - 1])
      } else {
        item.width = (item.width || 0) - roundedCumulativeErrors[index]
      }
    })
  }
}

export function flickrJustifiedLayout(
  aspectRatios: number[],
  config: FlickrJustifiedLayoutConfig
): FlickrJustifiedLayoutGeometry {
  const layoutItems: FlickrJustifiedLayoutBox[] = []
  const rows: Row[] = []
  let containerHeight = 0
  let currentRow: Row | null = null

  const createRow = () =>
    new Row({
      top: containerHeight,
      left: 0,
      width: config.containerWidth,
      spacing: config.boxSpacing,
      targetRowHeight: config.targetRowHeight,
      minRowHeight: config.minRowHeight,
      maxRowHeight: config.maxRowHeight,
      widowLayoutStyle: config.widowLayoutStyle
    })

  const addRow = (row: Row) => {
    rows.push(row)
    layoutItems.push(...row.getItems())
    containerHeight += row.height + config.boxSpacing
  }

  for (let index = 0; index < aspectRatios.length; index += 1) {
    const aspectRatio = aspectRatios[index]
    if (!Number.isFinite(aspectRatio) || aspectRatio <= 0) {
      throw new Error(`Item ${index} has an invalid aspect ratio`)
    }

    currentRow ??= createRow()
    const itemAdded = currentRow.addItem({ aspectRatio })

    if (currentRow.isLayoutComplete()) {
      addRow(currentRow)
      currentRow = createRow()

      if (!itemAdded) {
        currentRow.addItem({ aspectRatio })
        if (currentRow.isLayoutComplete()) {
          addRow(currentRow)
          currentRow = createRow()
        }
      }
    }
  }

  let widowCount = 0
  if (currentRow && currentRow.items.length && config.showWidows) {
    const previousRow = rows.at(-1)
    currentRow.forceComplete(previousRow?.height)
    widowCount = currentRow.items.length
    addRow(currentRow)
  }

  if (layoutItems.length) containerHeight -= config.boxSpacing

  return {
    containerHeight,
    widowCount,
    boxes: layoutItems
  }
}

function sumAspectRatios(items: LayoutItem[]): number {
  return items.reduce((sum, item) => sum + item.aspectRatio, 0)
}
