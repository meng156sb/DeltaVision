package com.deltavision.app.sync

import android.content.Context
import com.deltavision.app.model.Detection
import com.deltavision.app.model.FrameMeta
import com.deltavision.app.model.ReviewStatus
import com.deltavision.app.util.Jsons
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class SyncUploader(context: Context) {
    private val appContext = context.applicationContext
    private val gson = Jsons.gson
    private val store = UploadStore(appContext)
    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private var baseUrl: String = ""
    private var token: String = ""

    fun setCollectorEndpoint(baseUrl: String, token: String) {
        this.baseUrl = baseUrl.trimEnd('/')
        this.token = token
    }

    fun enqueue(frameMeta: FrameMeta, jpegBytes: ByteArray, detections: List<Detection>, reviewStatus: ReviewStatus, frameHash: String) {
        if (baseUrl.isBlank()) return
        val queueDir = File(appContext.filesDir, "queued_frames").apply { mkdirs() }
        val frameFile = File(queueDir, "${frameMeta.sessionId}_${frameMeta.timestampNs}.jpg")
        frameFile.writeBytes(jpegBytes)
        store.enqueue(
            frameHash = frameHash,
            framePath = frameFile.absolutePath,
            metadataJson = gson.toJson(frameMeta),
            detectionsJson = gson.toJson(detections),
            reviewStatus = reviewStatus.name,
        )
    }

    fun flush() {
        if (baseUrl.isBlank()) return
        for (record in store.listPending()) {
            val frameFile = File(record.framePath)
            if (!frameFile.exists()) {
                store.markSynced(record.id)
                continue
            }
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("review_status", record.reviewStatus)
                .addFormDataPart("frame_hash", record.frameHash)
                .addFormDataPart("metadata", record.metadataJson)
                .addFormDataPart("detections", record.detectionsJson)
                .addFormDataPart("image", frameFile.name, frameFile.asRequestBody("image/jpeg".toMediaType()))
                .build()
            val requestBuilder = Request.Builder()
                .url("$baseUrl/ingest/frame")
                .post(body)
            if (token.isNotBlank()) requestBuilder.header("X-Collector-Token", token)
            val response = runCatching { client.newCall(requestBuilder.build()).execute() }.getOrNull()
            if (response?.isSuccessful == true) {
                store.markSynced(record.id)
            } else {
                store.markFailed(record.id)
            }
            response?.close()
        }
    }
}
