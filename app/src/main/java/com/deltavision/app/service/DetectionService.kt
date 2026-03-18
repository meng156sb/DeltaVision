package com.deltavision.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.deltavision.app.R
import com.deltavision.app.capture.RootScreencapFrameSource
import com.deltavision.app.detector.NativeDetectorEngine
import com.deltavision.app.detector.SimpleTracker
import com.deltavision.app.model.AppConfig
import com.deltavision.app.model.Detection
import com.deltavision.app.model.FrameMeta
import com.deltavision.app.model.ReviewStatus
import com.deltavision.app.overlay.OverlayWindowController
import com.deltavision.app.prefs.AppSettings
import com.deltavision.app.sync.SyncUploader
import com.deltavision.app.util.ForegroundAppMonitor
import com.deltavision.app.util.ImageHash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class DetectionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frameSource = RootScreencapFrameSource()
    private val detectorEngine = NativeDetectorEngine()
    private val tracker = SimpleTracker()

    private lateinit var settings: AppSettings
    private lateinit var overlayController: OverlayWindowController
    private lateinit var syncUploader: SyncUploader

    private var loopJob: Job? = null
    private var config: AppConfig = AppConfig()
    private var lastUploadTimestampMs: Long = 0L
    private val lastTrackUploadMs = mutableMapOf<Long, Long>()
    private var lastFrameHash: String? = null
    private val sessionId: String = UUID.randomUUID().toString()
    private val deviceId: String = UUID.randomUUID().toString()

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        overlayController = OverlayWindowController(this)
        syncUploader = SyncUploader(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRuntime()
            else -> startRuntime(intent)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRuntime()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRuntime(intent: Intent?) {
        config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getSerializableExtra(EXTRA_CONFIG, AppConfig::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getSerializableExtra(EXTRA_CONFIG) as? AppConfig
        } ?: settings.load()
        settings.save(config)
        syncUploader.setCollectorEndpoint(config.collectorBaseUrl, config.collectorToken)
        ensureDetectorInit()
        startForeground(NOTIFICATION_ID, buildNotification())
        if (loopJob?.isActive != true) {
            loopJob = serviceScope.launch { runLoop() }
        }
    }

    private suspend fun runLoop() {
        while (serviceScope.isActive) {
            if (!ForegroundAppMonitor.isTargetForeground(this, config.gamePackage)) {
                val activePackage = ForegroundAppMonitor.currentForegroundPackage(this) ?: "unknown"
                overlayController.show(emptyList(), "???? target=${config.gamePackage} current=$activePackage")
                syncUploader.flush()
                delay(500)
                continue
            }

            val frame = frameSource.capture(config)
            if (frame == null) {
                overlayController.show(emptyList(), "抓帧失败")
                delay(frameDelayMs())
                continue
            }

            val detections = tracker.assign(detectorEngine.detectRoi(frame.rgbBytes, frame.roiPixelRect, frame.timestampNs))
            overlayController.show(
                detections,
                "det=${detections.size} fps=${config.targetFps} model=${if (detectorEngine.isReady()) "ready" else "missing"}",
            )
            maybeUpload(frame, detections)
            syncUploader.flush()
            delay(frameDelayMs())
        }
    }

    private fun ensureDetectorInit() {
        if (detectorEngine.isReady()) return
        val modelDir = File(getExternalFilesDir(null), "models").apply { mkdirs() }
        detectorEngine.initModel(
            modelDir.absolutePath,
            config.detectorInputSize,
            config.detectorConfThreshold,
            config.nmsThreshold,
            config.maxDetections,
        )
    }

    private fun maybeUpload(frame: com.deltavision.app.model.FramePacket, detections: List<Detection>) {
        if (detections.isEmpty()) return
        val now = System.currentTimeMillis()
        if (lastFrameHash != null && ImageHash.hammingDistance(lastFrameHash!!, frame.frameHash) <= 4) return
        if (now - lastUploadTimestampMs < 333) return

        val highest = detections.maxOf { it.confidence }
        val status = when {
            highest < 0.45f -> return
            highest < 0.75f -> ReviewStatus.PENDING_REVIEW
            else -> ReviewStatus.AUTO_ACCEPTED
        }
        val allowByTrack = detections.any {
            val last = lastTrackUploadMs[it.trackId] ?: 0L
            now - last >= 500
        }
        if (!allowByTrack) return

        val frameMeta = FrameMeta(
            sessionId = sessionId,
            deviceId = deviceId,
            gamePackage = config.gamePackage,
            screenWidth = frame.screenWidth,
            screenHeight = frame.screenHeight,
            roiNormRect = frame.roiNormRect,
            roiPixelRect = frame.roiPixelRect,
            orientation = "landscape",
            timestampNs = frame.timestampNs,
        )
        syncUploader.enqueue(frameMeta, frame.jpegBytes, detections, status, frame.frameHash)
        lastUploadTimestampMs = now
        lastFrameHash = frame.frameHash
        detections.forEach { lastTrackUploadMs[it.trackId] = now }
    }

    private fun frameDelayMs(): Long = (1000L / config.targetFps.coerceAtLeast(1))

    private fun stopRuntime() {
        loopJob?.cancel()
        loopJob = null
        detectorEngine.release()
        overlayController.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.service_channel_description)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "delta_vision_runtime"
        private const val NOTIFICATION_ID = 1007
        private const val EXTRA_CONFIG = "extra_config"
        private const val ACTION_START = "com.deltavision.action.START"
        private const val ACTION_STOP = "com.deltavision.action.STOP"

        fun buildStartIntent(context: Context, config: AppConfig): Intent = Intent(context, DetectionService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_CONFIG, config)
        }

        fun buildStopIntent(context: Context): Intent = Intent(context, DetectionService::class.java).apply {
            action = ACTION_STOP
        }
    }
}
