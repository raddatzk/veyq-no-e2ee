import CoreGraphics

/// Justified gallery layout — the same proven algorithm as web (greedy row-fill → justify to width;
/// trailing row is a widow at target height). Pure + deterministic.
enum JustifiedLayout {
    struct Source { let id: String; let aspectRatio: CGFloat }
    struct Tile: Identifiable { let id: String; let left, top, width, height: CGFloat; let widow: Bool }

    static func clampAspect(_ w: CGFloat, _ h: CGFloat) -> CGFloat {
        guard w > 0, h > 0 else { return 4.0 / 3.0 }
        return min(4.0 / 3.0, max(3.0 / 4.0, w / h))
    }

    static func calculate(_ items: [Source], containerWidth: CGFloat, targetHeight: CGFloat, gap: CGFloat) -> (tiles: [Tile], height: CGFloat) {
        let cw = max(0, containerWidth.rounded(.down))
        let th = max(0, targetHeight)
        let g = max(0, gap)
        guard !items.isEmpty, cw > 0, th > 0 else { return ([], 0) }

        let targetAR = cw / th
        var tiles: [Tile] = []
        var row: [Source] = []
        var rowAR: CGFloat = 0
        var top: CGFloat = 0

        func emit(_ row: [Source], force: Bool) -> CGFloat {
            let gapTotal = g * CGFloat(max(row.count - 1, 0))
            let available = max(cw - gapTotal, 0)
            let arSum = row.reduce(0) { $0 + $1.aspectRatio }
            let h = force && arSum > 0 ? available / arSum : th
            var left: CGFloat = 0
            for (idx, it) in row.enumerated() {
                let isLast = idx == row.count - 1
                let raw = it.aspectRatio * h
                let w = force && isLast ? max(cw - left, 0) : raw
                tiles.append(Tile(id: it.id, left: left, top: top, width: w, height: h, widow: !force))
                left += w + g
            }
            return h
        }

        for it in items {
            row.append(it)
            rowAR += it.aspectRatio
            if rowAR >= targetAR {
                top += emit(row, force: true) + g
                row = []
                rowAR = 0
            }
        }
        if !row.isEmpty { top += emit(row, force: false) }
        return (tiles, max(top, 0))
    }
}
