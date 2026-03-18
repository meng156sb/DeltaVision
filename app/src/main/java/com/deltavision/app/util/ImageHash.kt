package com.deltavision.app.util

import android.graphics.Bitmap
import kotlin.math.abs

object ImageHash {
    fun averageHash(bitmap: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val pixels = IntArray(64)
        scaled.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        var total = 0L
        val luminance = IntArray(64)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = (r * 30 + g * 59 + b * 11) / 100
            luminance[i] = y
            total += y
        }
        val avg = (total / 64L).toInt()
        return buildString(64) {
            luminance.forEach { append(if (it >= avg) '1' else '0') }
        }.also {
            scaled.recycle()
        }
    }

    fun hammingDistance(left: String, right: String): Int {
        val size = minOf(left.length, right.length)
        var distance = 0
        for (i in 0 until size) if (left[i] != right[i]) distance++
        return distance + abs(left.length - right.length)
    }
}
