package com.deltavision.app.detector

import android.util.Log
import com.deltavision.app.model.Detection
import com.deltavision.app.model.PixelRect
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

class NativeDetectorEngine : DetectorEngine {
    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String? = null
    private var ready = false
    private var inputSize: Int = 448
    private var confThreshold: Float = 0.35f
    private var nmsThreshold: Float = 0.45f
    private var maxDetections: Int = 10

    override fun initModel(modelPath: String, inputSize: Int, conf: Float, nms: Float, maxDetections: Int): Boolean {
        release()
        this.inputSize = inputSize
        confThreshold = conf
        nmsThreshold = nms
        this.maxDetections = maxDetections
        val modelDir = File(modelPath)
        val onnxFile = File(modelDir, MODEL_FILE_NAME)
        if (!onnxFile.exists()) {
            Log.w(TAG, "ONNX model missing: ${onnxFile.absolutePath}")
            ready = false
            return false
        }
        ready = try {
            environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = environment?.createSession(onnxFile.absolutePath, options)
            inputName = session?.inputNames?.firstOrNull()
            inputName != null
        } catch (error: OrtException) {
            Log.e(TAG, "Failed to initialize ONNX session", error)
            release()
            false
        } catch (error: RuntimeException) {
            Log.e(TAG, "Failed to initialize detector", error)
            release()
            false
        }
        return ready
    }

    override fun detectRoi(rgbFrame: ByteArray, roiRect: PixelRect, timestampNs: Long): List<Detection> {
        val currentSession = session ?: return emptyList()
        val currentEnvironment = environment ?: return emptyList()
        val currentInputName = inputName ?: return emptyList()
        if (!ready) return emptyList()
        if (rgbFrame.size != inputSize * inputSize * 3) {
            Log.w(TAG, "Unexpected RGB input size: ${rgbFrame.size}")
            return emptyList()
        }

        return try {
            val inputTensor = OnnxTensor.createTensor(
                currentEnvironment,
                FloatBuffer.wrap(toNchwFloatArray(rgbFrame)),
                longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong()),
            )
            inputTensor.use { tensor ->
                currentSession.run(mapOf(currentInputName to tensor)).use { result ->
                    val output = result.firstOrNull()?.value ?: return emptyList()
                    decodeOutput(output, roiRect, timestampNs)
                }
            }
        } catch (error: OrtException) {
            Log.e(TAG, "ONNX inference failed", error)
            emptyList()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Detector runtime failed", error)
            emptyList()
        }
    }

    override fun isReady(): Boolean = ready

    override fun release() {
        runCatching { session?.close() }
        runCatching { environment?.close() }
        session = null
        environment = null
        inputName = null
        ready = false
    }

    private fun toNchwFloatArray(rgbFrame: ByteArray): FloatArray {
        val planeSize = inputSize * inputSize
        val output = FloatArray(planeSize * 3)
        var pixelIndex = 0
        var redIndex = 0
        var greenIndex = planeSize
        var blueIndex = planeSize * 2
        while (pixelIndex < rgbFrame.size) {
            output[redIndex++] = (rgbFrame[pixelIndex].toInt() and 0xFF) / 255.0f
            output[greenIndex++] = (rgbFrame[pixelIndex + 1].toInt() and 0xFF) / 255.0f
            output[blueIndex++] = (rgbFrame[pixelIndex + 2].toInt() and 0xFF) / 255.0f
            pixelIndex += 3
        }
        return output
    }

    private fun decodeOutput(output: Any, roiRect: PixelRect, timestampNs: Long): List<Detection> {
        val batch = when (output) {
            is Array<*> -> output.firstOrNull()
            else -> null
        } ?: return emptyList()

        val candidates = when (batch) {
            is Array<*> -> decodeArrayOutput(batch, roiRect, timestampNs)
            else -> emptyList()
        }
        return nonMaxSuppression(candidates)
            .take(maxDetections)
            .map {
                Detection(
                    left = it.left,
                    top = it.top,
                    right = it.right,
                    bottom = it.bottom,
                    confidence = it.confidence,
                    timestampNs = timestampNs,
                )
            }
    }

    private fun decodeArrayOutput(batch: Array<*>, roiRect: PixelRect, timestampNs: Long): List<Candidate> {
        val first = batch.firstOrNull() ?: return emptyList()
        if (first !is FloatArray) return emptyList()
        val rowCount = batch.size
        val columnCount = first.size
        val channelsFirst = rowCount <= 128 && columnCount > rowCount
        return if (channelsFirst) {
            decodeChannelsFirst(batch, roiRect, timestampNs)
        } else {
            decodeRowsFirst(batch, roiRect, timestampNs)
        }
    }

    private fun decodeChannelsFirst(batch: Array<*>, roiRect: PixelRect, timestampNs: Long): List<Candidate> {
        if (batch.size < 5) return emptyList()
        val xRow = batch[0] as? FloatArray ?: return emptyList()
        val yRow = batch[1] as? FloatArray ?: return emptyList()
        val wRow = batch[2] as? FloatArray ?: return emptyList()
        val hRow = batch[3] as? FloatArray ?: return emptyList()
        val count = xRow.size
        val tailRows = batch.drop(4).mapNotNull { it as? FloatArray }
        if (tailRows.isEmpty()) return emptyList()
        val candidates = ArrayList<Candidate>(min(count, maxDetections * 32))
        for (index in 0 until count) {
            val score = scoreFromTail(tailRows, index)
            if (score < confThreshold) continue
            buildCandidate(xRow[index], yRow[index], wRow[index], hRow[index], score, roiRect, timestampNs)?.let(candidates::add)
        }
        return candidates
    }

    private fun decodeRowsFirst(batch: Array<*>, roiRect: PixelRect, timestampNs: Long): List<Candidate> {
        val candidates = ArrayList<Candidate>(min(batch.size, maxDetections * 32))
        for (row in batch) {
            val values = row as? FloatArray ?: continue
            if (values.size < 5) continue
            val score = scoreFromRow(values)
            if (score < confThreshold) continue
            buildCandidate(values[0], values[1], values[2], values[3], score, roiRect, timestampNs)?.let(candidates::add)
        }
        return candidates
    }

    private fun scoreFromTail(tailRows: List<FloatArray>, index: Int): Float {
        if (tailRows.isEmpty()) return 0.0f
        if (tailRows.size == 1) return tailRows[0].getOrElse(index) { 0.0f }
        if (tailRows.size >= COCO_CLASS_COUNT) {
            return tailRows[COCO_PERSON_CLASS_INDEX].getOrElse(index) { 0.0f }
        }
        val objectness = tailRows[0].getOrElse(index) { 0.0f }
        val personScore = tailRows.getOrElse(1) { tailRows[0] }.getOrElse(index) { 0.0f }
        return max(personScore, objectness * personScore)
    }

    private fun scoreFromRow(values: FloatArray): Float {
        val tail = values.copyOfRange(4, values.size)
        if (tail.isEmpty()) return 0.0f
        if (tail.size == 1) return tail[0]
        if (tail.size >= COCO_CLASS_COUNT) {
            return tail.getOrElse(COCO_PERSON_CLASS_INDEX) { 0.0f }
        }
        val objectness = tail[0]
        val personScore = tail.getOrElse(1) { tail[0] }
        return max(personScore, objectness * personScore)
    }

    private fun buildCandidate(
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float,
        confidence: Float,
        roiRect: PixelRect,
        timestampNs: Long,
    ): Candidate? {
        val halfWidth = width / 2.0f
        val halfHeight = height / 2.0f
        val scaleX = roiRect.width.toFloat() / inputSize.toFloat()
        val scaleY = roiRect.height.toFloat() / inputSize.toFloat()
        val left = ((centerX - halfWidth) * scaleX).coerceIn(0.0f, roiRect.width.toFloat())
        val top = ((centerY - halfHeight) * scaleY).coerceIn(0.0f, roiRect.height.toFloat())
        val right = ((centerX + halfWidth) * scaleX).coerceIn(0.0f, roiRect.width.toFloat())
        val bottom = ((centerY + halfHeight) * scaleY).coerceIn(0.0f, roiRect.height.toFloat())
        if (right <= left || bottom <= top) return null
        return Candidate(left, top, right, bottom, confidence, timestampNs)
    }

    private fun nonMaxSuppression(candidates: List<Candidate>): List<Candidate> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedByDescending { it.confidence }
        val kept = ArrayList<Candidate>(min(sorted.size, maxDetections))
        for (candidate in sorted) {
            if (kept.size >= maxDetections) break
            val overlaps = kept.any { intersectionOverUnion(it, candidate) > nmsThreshold }
            if (!overlaps) kept.add(candidate)
        }
        return kept
    }

    private fun intersectionOverUnion(first: Candidate, second: Candidate): Float {
        val overlapLeft = max(first.left, second.left)
        val overlapTop = max(first.top, second.top)
        val overlapRight = min(first.right, second.right)
        val overlapBottom = min(first.bottom, second.bottom)
        val overlapWidth = max(0.0f, overlapRight - overlapLeft)
        val overlapHeight = max(0.0f, overlapBottom - overlapTop)
        val intersection = overlapWidth * overlapHeight
        if (intersection <= 0.0f) return 0.0f
        val firstArea = (first.right - first.left) * (first.bottom - first.top)
        val secondArea = (second.right - second.left) * (second.bottom - second.top)
        val union = firstArea + secondArea - intersection
        return if (union <= 0.0f) 0.0f else intersection / union
    }

    private data class Candidate(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val confidence: Float,
        val timestampNs: Long,
    )

    companion object {
        private const val TAG = "NativeDetectorEngine"
        private const val MODEL_FILE_NAME = "model.onnx"
        private const val COCO_PERSON_CLASS_INDEX = 0
        private const val COCO_CLASS_COUNT = 80
    }
}
