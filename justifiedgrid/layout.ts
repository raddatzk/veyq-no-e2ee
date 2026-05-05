import type { GalleryItem, LayoutResult, LayoutTile } from './types';

const TARGET_ROW_HEIGHT = 260;
const GAP = 8;

function layoutRow(
  row: GalleryItem[],
  top: number,
  containerWidth: number,
  forceJustify: boolean,
): { tiles: LayoutTile[]; height: number } {
  const rowGapTotal = GAP * Math.max(row.length - 1, 0);
  const availableWidth = Math.max(containerWidth - rowGapTotal, 0);
  const aspectRatioSum = row.reduce((sum, item) => sum + item.aspectRatio, 0);
  const height = forceJustify && aspectRatioSum > 0
    ? availableWidth / aspectRatioSum
    : TARGET_ROW_HEIGHT;

  let left = 0;

  const tiles = row.map((item, index) => {
    const isLast = index === row.length - 1;
    const rawWidth = item.aspectRatio * height;
    const renderedWidth = forceJustify && isLast
      ? Math.max(containerWidth - left, 0)
      : rawWidth;

    const tile: LayoutTile = {
      ...item,
      left,
      top,
      renderedWidth,
      renderedHeight: height,
    };

    left += renderedWidth + GAP;
    return tile;
  });

  return { tiles, height };
}

export function calculateJustifiedLayout(items: GalleryItem[], containerWidth: number): LayoutResult {
  if (items.length === 0 || containerWidth <= 0) {
    return { tiles: [], height: 0 };
  }

  const targetAspectRatio = containerWidth / TARGET_ROW_HEIGHT;
  const tiles: LayoutTile[] = [];
  let row: GalleryItem[] = [];
  let rowAspectRatio = 0;
  let top = 0;

  for (const item of items) {
    row.push(item);
    rowAspectRatio += item.aspectRatio;

    if (rowAspectRatio >= targetAspectRatio) {
      const laidOutRow = layoutRow(row, top, containerWidth, true);
      tiles.push(...laidOutRow.tiles);
      top += laidOutRow.height + GAP;
      row = [];
      rowAspectRatio = 0;
    }
  }

  if (row.length > 0) {
    const laidOutRow = layoutRow(row, top, containerWidth, false);
    tiles.push(...laidOutRow.tiles);
    top += laidOutRow.height;
  } else if (tiles.length > 0) {
    top -= GAP;
  }

  return {
    tiles,
    height: Math.max(top, 0),
  };
}
