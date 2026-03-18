package com.deltavision.app.model

import java.io.Serializable

data class RoiConfig(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val width: Float = 0.38f,
    val height: Float = 0.52f,
) : Serializable {
    fun toPixelRect(screenWidth: Int, screenHeight: Int): PixelRect {
        val pixelWidth = (screenWidth * width).toInt().coerceIn(64, screenWidth)
        val pixelHeight = (screenHeight * height).toInt().coerceIn(64, screenHeight)
        val left = ((screenWidth * centerX) - pixelWidth / 2f).toInt().coerceIn(0, screenWidth - pixelWidth)
        val top = ((screenHeight * centerY) - pixelHeight / 2f).toInt().coerceIn(0, screenHeight - pixelHeight)
        return PixelRect(left = left, top = top, width = pixelWidth, height = pixelHeight)
    }
}
