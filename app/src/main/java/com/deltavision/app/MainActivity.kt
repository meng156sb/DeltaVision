package com.deltavision.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.deltavision.app.databinding.ActivityMainBinding
import com.deltavision.app.model.AppConfig
import com.deltavision.app.model.RoiConfig
import com.deltavision.app.prefs.AppSettings
import com.deltavision.app.service.DetectionService
import com.deltavision.app.util.ForegroundAppMonitor
import com.deltavision.app.util.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AppSettings(this)
        render(settings.load())
        bindActions()
    }

    private fun render(config: AppConfig, running: Boolean = false) {
        binding.gamePackageInput.setText(config.gamePackage)
        binding.collectorUrlInput.setText(config.collectorBaseUrl)
        binding.collectorTokenInput.setText(config.collectorToken)
        binding.roiWidthInput.setText(config.roiConfig.width.toString())
        binding.roiHeightInput.setText(config.roiConfig.height.toString())
        binding.targetFpsInput.setText(config.targetFps.toString())
        binding.coldStartCollectionCheckbox.isChecked = config.coldStartCollectionEnabled
        val modelPath = "${getExternalFilesDir(null)?.absolutePath}/models/model.onnx"
        binding.statusText.text = buildString {
            appendLine("Status: ${if (running) "running" else "stopped"}")
            appendLine("Game package: ${config.gamePackage}")
            appendLine("Collector: ${if (config.collectorBaseUrl.isBlank()) "not set" else config.collectorBaseUrl}")
            appendLine("ROI: ${config.roiConfig.width} x ${config.roiConfig.height}")
            appendLine("Target FPS: ${config.targetFps}")
            appendLine("Cold-start upload: ${if (config.coldStartCollectionEnabled) "on" else "off"}")
            append("Model path: $modelPath")
        }
    }

    private fun bindActions() {
        binding.permissionsButton.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            if (!ForegroundAppMonitor.hasUsageAccess(this)) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            Toast.makeText(this, "Opened permission pages", Toast.LENGTH_SHORT).show()
        }

        binding.rootButton.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val ok = RootShell.canUseRoot()
                launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, if (ok) "Root ready" else "Root unavailable", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.startButton.setOnClickListener {
            val config = readConfig()
            settings.save(config)
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant overlay permission first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!ForegroundAppMonitor.hasUsageAccess(this)) {
                Toast.makeText(this, "Grant usage access first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ContextCompat.startForegroundService(this, DetectionService.buildStartIntent(this, config))
            render(config, running = true)
        }

        binding.stopButton.setOnClickListener {
            startService(DetectionService.buildStopIntent(this))
            render(settings.load(), running = false)
        }
    }

    private fun readConfig(): AppConfig {
        val current = settings.load()
        return current.copy(
            gamePackage = binding.gamePackageInput.text?.toString()?.trim().orEmpty(),
            collectorBaseUrl = binding.collectorUrlInput.text?.toString()?.trim().orEmpty(),
            collectorToken = binding.collectorTokenInput.text?.toString()?.trim().orEmpty(),
            roiConfig = RoiConfig(
                width = binding.roiWidthInput.text?.toString()?.toFloatOrNull() ?: current.roiConfig.width,
                height = binding.roiHeightInput.text?.toString()?.toFloatOrNull() ?: current.roiConfig.height,
            ),
            targetFps = binding.targetFpsInput.text?.toString()?.toIntOrNull()?.coerceIn(1, 30) ?: current.targetFps,
            coldStartCollectionEnabled = binding.coldStartCollectionCheckbox.isChecked,
        )
    }
}
