package com.deltavision.app.model

data class FramePacket(
    val rgbBytes: ByteArray,
    val jpegBytes: ByteArray,
    val screenWidth: Int,
    val screenHeight: Int,
    val roiPixelRect: PixelRect,
    val roiNormRect: RoiConfig,
    val roiWidth: Int,
    val roiHeight: Int,
    val timestampNs: Long,
    val frameHash: String,
)
