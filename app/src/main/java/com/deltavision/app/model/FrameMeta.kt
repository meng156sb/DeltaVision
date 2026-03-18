package com.deltavision.app.model

data class FrameMeta(
    val sessionId: String,
    val deviceId: String,
    val gamePackage: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val roiNormRect: RoiConfig,
    val roiPixelRect: PixelRect,
    val orientation: String,
    val timestampNs: Long,
)
