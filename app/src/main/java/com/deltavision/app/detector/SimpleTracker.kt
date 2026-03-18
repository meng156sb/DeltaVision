package com.deltavision.app.detector

import com.deltavision.app.model.Detection
import kotlin.math.max
import kotlin.math.min

class SimpleTracker {
    private var nextTrackId = 1L
    private val tracks = mutableMapOf<Long, Detection>()

    fun assign(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) {
            tracks.clear()
            return emptyList()
        }
        val updated = ArrayList<Detection>(detections.size)
        val remaining = tracks.toMutableMap()
        for (detection in detections) {
            val match = remaining.entries.maxByOrNull { (_, previous) -> iou(previous, detection) }
            val trackId = if (match != null && iou(match.value, detection) >= 0.3f) {
                remaining.remove(match.key)
                match.key
            } else {
                val created = nextTrackId
                nextTrackId += 1
                created
            }
            val tracked = detection.copy(trackId = trackId)
            updated += tracked
            tracks[trackId] = tracked
        }
        tracks.keys.retainAll(updated.map { it.trackId }.toSet())
        return updated
    }

    private fun iou(left: Detection, right: Detection): Float {
        val interLeft = max(left.left, right.left)
        val interTop = max(left.top, right.top)
        val interRight = min(left.right, right.right)
        val interBottom = min(left.bottom, right.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f
        val intersection = (interRight - interLeft) * (interBottom - interTop)
        val leftArea = (left.right - left.left) * (left.bottom - left.top)
        val rightArea = (right.right - right.left) * (right.bottom - right.top)
        val union = leftArea + rightArea - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}
