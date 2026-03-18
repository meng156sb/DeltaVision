package com.deltavision.app.util

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

object BitmapUtils {
    fun toJpegBytes(bitmap: Bitmap, quality: Int = 92): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    fun toRgbBytes(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = ByteArray(width * height * 3)
        var dst = 0
        for (pixel in pixels) {
            out[dst++] = ((pixel shr 16) and 0xFF).toByte()
            out[dst++] = ((pixel shr 8) and 0xFF).toByte()
            out[dst++] = (pixel and 0xFF).toByte()
        }
        return out
    }
}
