package com.veyq.api.library.media

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage

object MediaImageOrientation {
    private val rotatedOrientations = setOf(5, 6, 7, 8)

    fun visibleDimensions(width: Int, height: Int, orientation: Int?): Pair<Int, Int> =
        if (orientation in rotatedOrientations) height to width else width to height

    fun apply(source: BufferedImage, orientation: Int?): BufferedImage {
        val value = orientation ?: 1
        if (value == 1) return source

        val targetSize = visibleDimensions(source.width, source.height, value)
        val target = BufferedImage(targetSize.first, targetSize.second, BufferedImage.TYPE_INT_ARGB)

        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val (targetX, targetY) = targetCoordinates(x, y, source.width, source.height, value)
                target.setRGB(targetX, targetY, source.getRGB(x, y))
            }
        }

        return target
    }

    private fun targetCoordinates(x: Int, y: Int, width: Int, height: Int, orientation: Int): Pair<Int, Int> =
        when (orientation) {
            2 -> width - 1 - x to y
            3 -> width - 1 - x to height - 1 - y
            4 -> x to height - 1 - y
            5 -> y to x
            6 -> height - 1 - y to x
            7 -> height - 1 - y to width - 1 - x
            8 -> y to width - 1 - x
            else -> x to y
        }

    fun resize(source: BufferedImage, targetWidth: Int, targetHeight: Int): BufferedImage {
        val preview = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
        val graphics = preview.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, targetWidth, targetHeight)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null)
        } finally {
            graphics.dispose()
        }
        return preview
    }
}
