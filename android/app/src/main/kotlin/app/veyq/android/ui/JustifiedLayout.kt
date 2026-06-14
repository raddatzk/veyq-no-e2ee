package app.veyq.android.ui

import kotlin.math.floor

/** Justified gallery layout — the same proven algorithm as web/iOS. Works in dp units. Pure. */
object JustifiedLayout {
    data class Source(val id: String, val aspectRatio: Float)
    data class Tile(val id: String, val left: Float, val top: Float, val width: Float, val height: Float, val widow: Boolean)

    fun clampAspect(w: Float, h: Float): Float = if (w > 0 && h > 0) minOf(4f / 3f, maxOf(3f / 4f, w / h)) else 4f / 3f

    fun calculate(items: List<Source>, containerWidth: Float, targetHeight: Float, gap: Float): Pair<List<Tile>, Float> {
        val cw = maxOf(0f, floor(containerWidth))
        val th = maxOf(0f, targetHeight)
        val g = maxOf(0f, gap)
        if (items.isEmpty() || cw <= 0f || th <= 0f) return emptyList<Tile>() to 0f

        val targetAR = cw / th
        val tiles = mutableListOf<Tile>()
        var row = mutableListOf<Source>()
        var rowAR = 0f
        var top = 0f

        fun emit(r: List<Source>, force: Boolean): Float {
            val gapTotal = g * maxOf(r.size - 1, 0)
            val available = maxOf(cw - gapTotal, 0f)
            val arSum = r.fold(0f) { s, it -> s + it.aspectRatio }
            val h = if (force && arSum > 0f) available / arSum else th
            var left = 0f
            r.forEachIndexed { idx, it ->
                val isLast = idx == r.size - 1
                val raw = it.aspectRatio * h
                val w = if (force && isLast) maxOf(cw - left, 0f) else raw
                tiles.add(Tile(it.id, left, top, w, h, !force))
                left += w + g
            }
            return h
        }

        for (it in items) {
            row.add(it)
            rowAR += it.aspectRatio
            if (rowAR >= targetAR) {
                top += emit(row, true) + g
                row = mutableListOf()
                rowAR = 0f
            }
        }
        if (row.isNotEmpty()) top += emit(row, false)
        return tiles to maxOf(top, 0f)
    }
}
