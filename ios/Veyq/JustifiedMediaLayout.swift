import Foundation

struct MediaGridLayoutSource: Identifiable, Equatable {
    let id: String
    let aspectRatio: Double?
}

struct MediaGridLayoutTile: Identifiable, Equatable {
    let id: String
    let left: Double
    let top: Double
    let renderedWidth: Double
    let renderedHeight: Double
    let widow: Bool
}

struct MediaGridLayoutResult: Equatable {
    let tiles: [MediaGridLayoutTile]
    let height: Double
}

enum JustifiedMediaLayout {
    static let fallbackAspectRatio = 4.0 / 3.0
    static let minGalleryAspectRatio = 3.0 / 4.0
    static let maxGalleryAspectRatio = 4.0 / 3.0

    static func calculate(
        items: [MediaGridLayoutSource],
        containerWidth: Double,
        targetHeight: Double,
        gap: Double,
        showWidows: Bool = true,
        maxRows: Int? = nil
    ) -> MediaGridLayoutResult {
        let containerWidth = max(0, floor(containerWidth))
        let targetHeight = max(0, targetHeight)
        let gap = max(0, gap)
        let normalizedItems = items.map {
            MediaGridLayoutSource(id: $0.id, aspectRatio: safeAspectRatio($0.aspectRatio))
        }

        guard !normalizedItems.isEmpty, containerWidth > 0, targetHeight > 0 else {
            return MediaGridLayoutResult(tiles: [], height: 0)
        }

        let targetAspectRatio = containerWidth / targetHeight
        var tiles: [MediaGridLayoutTile] = []
        var row: [MediaGridLayoutSource] = []
        var rowAspectRatio = 0.0
        var top = 0.0
        var renderedRows = 0

        for item in normalizedItems {
            if let maxRows, renderedRows >= maxRows {
                break
            }

            row.append(item)
            rowAspectRatio += item.aspectRatio ?? fallbackAspectRatio

            if rowAspectRatio >= targetAspectRatio {
                let laidOutRow = layoutRow(
                    row: row,
                    top: top,
                    containerWidth: containerWidth,
                    targetHeight: targetHeight,
                    gap: gap,
                    forceJustify: true
                )
                tiles.append(contentsOf: laidOutRow.tiles)
                top += laidOutRow.height + gap
                renderedRows += 1
                row = []
                rowAspectRatio = 0
            }
        }

        if let maxRows, renderedRows >= maxRows {
            if !tiles.isEmpty {
                top -= gap
            }
        } else if !row.isEmpty, showWidows {
            let laidOutRow = layoutRow(
                row: row,
                top: top,
                containerWidth: containerWidth,
                targetHeight: targetHeight,
                gap: gap,
                forceJustify: false
            )
            tiles.append(contentsOf: laidOutRow.tiles)
            top += laidOutRow.height
        } else if row.isEmpty, !tiles.isEmpty {
            top -= gap
        }

        return MediaGridLayoutResult(tiles: tiles, height: max(top, 0))
    }

    static func galleryDisplayAspectRatio(width: Int?, height: Int?) -> Double {
        guard let width, let height, width > 0, height > 0 else {
            return fallbackAspectRatio
        }

        let aspectRatio = Double(width) / Double(height)
        guard aspectRatio.isFinite, aspectRatio > 0 else {
            return fallbackAspectRatio
        }

        return min(maxGalleryAspectRatio, max(minGalleryAspectRatio, aspectRatio))
    }

    private static func layoutRow(
        row: [MediaGridLayoutSource],
        top: Double,
        containerWidth: Double,
        targetHeight: Double,
        gap: Double,
        forceJustify: Bool
    ) -> (tiles: [MediaGridLayoutTile], height: Double) {
        let rowGapTotal = gap * Double(max(row.count - 1, 0))
        let availableWidth = max(containerWidth - rowGapTotal, 0)
        let aspectRatioSum = row.reduce(0.0) { $0 + ($1.aspectRatio ?? fallbackAspectRatio) }
        let height = forceJustify && aspectRatioSum > 0 ? availableWidth / aspectRatioSum : targetHeight

        var left = 0.0
        let tiles = row.enumerated().map { index, item in
            let isLast = index == row.count - 1
            let rawWidth = (item.aspectRatio ?? fallbackAspectRatio) * height
            let renderedWidth = forceJustify && isLast ? max(containerWidth - left, 0) : rawWidth
            let tile = MediaGridLayoutTile(
                id: item.id,
                left: left,
                top: top,
                renderedWidth: renderedWidth,
                renderedHeight: height,
                widow: !forceJustify
            )

            left += renderedWidth + gap
            return tile
        }

        return (tiles, height)
    }

    private static func safeAspectRatio(_ value: Double?) -> Double {
        guard let value, value.isFinite, value > 0 else {
            return fallbackAspectRatio
        }
        return value
    }
}
