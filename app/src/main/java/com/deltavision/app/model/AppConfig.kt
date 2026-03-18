package com.deltavision.app.model

import java.io.Serializable

data class AppConfig(
    val gamePackage: String = "com.tencent.tmgp.dfm",
    val collectorBaseUrl: String = "",
    val collectorToken: String = "",
    val roiConfig: RoiConfig = RoiConfig(),
    val targetFps: Int = 15,
    val detectorInputSize: Int = 448,
    val detectorConfThreshold: Float = 0.35f,
    val nmsThreshold: Float = 0.45f,
    val maxDetections: Int = 10,
    val coldStartCollectionEnabled: Boolean = true,
) : Serializable
