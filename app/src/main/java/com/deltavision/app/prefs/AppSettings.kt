package com.deltavision.app.prefs

import android.content.Context
import com.deltavision.app.model.AppConfig
import com.deltavision.app.model.RoiConfig

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("delta_vision_settings", Context.MODE_PRIVATE)

    fun load(): AppConfig = AppConfig(
        gamePackage = prefs.getString("gamePackage", "com.tencent.tmgp.dfm").orEmpty(),
        collectorBaseUrl = prefs.getString("collectorBaseUrl", "").orEmpty(),
        collectorToken = prefs.getString("collectorToken", "").orEmpty(),
        roiConfig = RoiConfig(
            centerX = prefs.getFloat("roiCenterX", 0.5f),
            centerY = prefs.getFloat("roiCenterY", 0.5f),
            width = prefs.getFloat("roiWidth", 0.38f),
            height = prefs.getFloat("roiHeight", 0.52f),
        ),
        targetFps = prefs.getInt("targetFps", 15),
        detectorInputSize = prefs.getInt("detectorInputSize", 448),
        detectorConfThreshold = prefs.getFloat("detectorConfThreshold", 0.35f),
        nmsThreshold = prefs.getFloat("nmsThreshold", 0.45f),
        maxDetections = prefs.getInt("maxDetections", 10),
    )

    fun save(config: AppConfig) {
        prefs.edit()
            .putString("gamePackage", config.gamePackage)
            .putString("collectorBaseUrl", config.collectorBaseUrl)
            .putString("collectorToken", config.collectorToken)
            .putFloat("roiCenterX", config.roiConfig.centerX)
            .putFloat("roiCenterY", config.roiConfig.centerY)
            .putFloat("roiWidth", config.roiConfig.width)
            .putFloat("roiHeight", config.roiConfig.height)
            .putInt("targetFps", config.targetFps)
            .putInt("detectorInputSize", config.detectorInputSize)
            .putFloat("detectorConfThreshold", config.detectorConfThreshold)
            .putFloat("nmsThreshold", config.nmsThreshold)
            .putInt("maxDetections", config.maxDetections)
            .apply()
    }
}
