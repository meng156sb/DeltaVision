package com.deltavision.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import com.deltavision.app.model.Detection

class OverlayWindowController(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: OverlayView? = null

    fun show(detections: List<Detection>, statsText: String) {
        if (!Settings.canDrawOverlays(context)) return
        mainHandler.post {
            if (overlayView == null) {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP or Gravity.START }
                overlayView = OverlayView(context)
                windowManager.addView(overlayView, params)
            }
            overlayView?.update(detections, statsText)
        }
    }

    fun hide() {
        mainHandler.post {
            overlayView?.let(windowManager::removeView)
            overlayView = null
        }
    }
}
