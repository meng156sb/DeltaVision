package com.deltavision.app.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.deltavision.app.model.AppConfig
import com.deltavision.app.model.FramePacket
import com.deltavision.app.util.BitmapUtils
import com.deltavision.app.util.ImageHash
import com.deltavision.app.util.RootShell

class RootScreencapFrameSource {
    fun capture(config: AppConfig): FramePacket? {
        val pngBytes = RootShell.execForBytes("screencap -p") ?: return null
        val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size) ?: return null
        try {
            val roiPixelRect = config.roiConfig.toPixelRect(bitmap.width, bitmap.height)
            val roiBitmap = Bitmap.createBitmap(bitmap, roiPixelRect.left, roiPixelRect.top, roiPixelRect.width, roiPixelRect.height)
            try {
                val scaled = Bitmap.createScaledBitmap(roiBitmap, config.detectorInputSize, config.detectorInputSize, true)
                try {
                    return FramePacket(
                        rgbBytes = BitmapUtils.toRgbBytes(scaled),
                        jpegBytes = BitmapUtils.toJpegBytes(roiBitmap),
                        screenWidth = bitmap.width,
                        screenHeight = bitmap.height,
                        roiPixelRect = roiPixelRect,
                        roiNormRect = config.roiConfig,
                        roiWidth = roiBitmap.width,
                        roiHeight = roiBitmap.height,
                        timestampNs = System.nanoTime(),
                        frameHash = ImageHash.averageHash(roiBitmap),
                    )
                } finally {
                    scaled.recycle()
                }
            } finally {
                roiBitmap.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
