package com.deltavision.app.detector

import com.deltavision.app.model.Detection
import com.deltavision.app.model.PixelRect

interface DetectorEngine {
    fun initModel(modelPath: String, inputSize: Int, conf: Float, nms: Float, maxDetections: Int): Boolean
    fun detectRoi(rgbFrame: ByteArray, roiRect: PixelRect, timestampNs: Long): List<Detection>
    fun isReady(): Boolean
    fun release()
}
