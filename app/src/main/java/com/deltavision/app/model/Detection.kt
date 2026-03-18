package com.deltavision.app.model

data class Detection(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
    val label: String = "person_body",
    val trackId: Long = -1,
    val timestampNs: Long,
)
