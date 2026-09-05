package com.prince.eyenav

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var overlayButton: Button

    private lateinit var eyeTracker: EyeTracker
    private var cameraProvider: ProcessCameraProvider? = null
    private var calibrationSamples = 0
    private val requiredSamples = 55
    private var calibrationActive = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCalibrationCamera() else toast("Camera permission is required")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CalibrationManager.load(this)
        buildUi()
        refreshPermissionUi()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (!CalibrationManager.isCalibrated) startCalibrationCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 30, 28, 24)
            setBackgroundColor(Color.rgb(12, 12, 16))
        }

        val title = TextView(this).apply {
            text = "EyeNav"
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(-1, 70))

        val subtitle = TextView(this).apply {
            text = "Eye-controlled Android navigation"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        root.addView(subtitle, LinearLayout.LayoutParams(-1, 50))

        preview = PreviewView(this)
        root.addView(
            preview,
            LinearLayout.LayoutParams(-1, 0).apply { weight = 1f; topMargin = 14; bottomMargin = 14 }
        )

        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(12, 12, 12, 12)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, 70))

        accessibilityButton = button("Enable Accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        root.addView(accessibilityButton)

        overlayButton = button("Allow Floating Cursor") {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
        root.addView(overlayButton)

        startButton = button("Start EyeNav") {
            startEyeNav()
        }
        root.addView(startButton)

        val recalibrate = button("Recalibrate") {
            CalibrationManager.reset(this)
            calibrationSamples = 0
            calibrationActive = true
            startCalibrationCamera()
        }
        root.addView(recalibrate)

        setContentView(root)
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 15f
        setOnClickListener { action() }
        isAllCaps = false
        setPadding(10, 4, 10, 4)
    }

    private fun startCalibrationCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }

        calibrationActive = true
        calibrationSamples = 0
        status.text = "Starting calibration..."

        if (!::eyeTracker.isInitialized) {
            eyeTracker = EyeTracker(this)
            eyeTracker.setup()
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()

            val previewUseCase = Preview.Builder().build().also {
                it.setSurfaceProvider(preview.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { image ->
                try {
                    val bitmap = image.toBitmap()
                    eyeTracker.processFrame(
                        com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build(),
                        System.currentTimeMillis()
                    )
                    processCalibrationFrame()
                } catch (_: Exception) {
                } finally {
                    image.close()
                }
            }

            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                previewUseCase,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processCalibrationFrame() {
        if (!calibrationActive || !EyeNavState.faceDetected) return
        if (preview.width <= 0 || preview.height <= 0) return

        val target = CalibrationManager.target()
        status.text = "Calibration ${CalibrationManager.currentTarget + 1}/9 — look at the red target (${(target.first * 100).toInt()}%, ${(target.second * 100).toInt()}%)"
        calibrationSamples++

        if (calibrationSamples >= requiredSamples) {
            CalibrationManager.addPoint(EyeNavState.gazeX, EyeNavState.gazeY)
            calibrationSamples = 0

            if (CalibrationManager.isCalibrated) {
                calibrationActive = false
                CalibrationManager.save(this)
                stopCalibrationCamera()
                status.text = "Calibration complete. Enable the two permissions, then start EyeNav."
                refreshPermissionUi()
            }
        }
    }

    private fun stopCalibrationCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        if (::eyeTracker.isInitialized) {
            eyeTracker.close()
        }
    }

    private fun startEyeNav() {
        if (!CalibrationManager.isCalibrated) {
            toast("Complete calibration first")
            return
        }
        if (!isAccessibilityEnabled()) {
            toast("Enable EyeNav Accessibility Service first")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            toast("Allow EyeNav to display over other apps first")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        stopCalibrationCamera()
        val intent = Intent(this, EyeNavTrackingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "EyeNav started. You can leave this app now.", Toast.LENGTH_LONG).show()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expected = ComponentName(this, EyeNavAccessibilityService::class.java)
        return services.any { info -> info.resolveInfo.serviceInfo.let { ComponentName(it.packageName, it.name) == expected } }
    }

    private fun refreshPermissionUi() {
        accessibilityButton.text = if (isAccessibilityEnabled()) "Accessibility: ENABLED" else "Enable Accessibility"
        val overlayGranted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        overlayButton.text = if (overlayGranted) "Floating Cursor: ALLOWED" else "Allow Floating Cursor"
        startButton.isEnabled = CalibrationManager.isCalibrated
        if (CalibrationManager.isCalibrated && !calibrationActive) {
            status.text = "Calibration saved. Ready for EyeNav."
        }
    }

    override fun onResume() {
        super.onResume()
        if (::accessibilityButton.isInitialized) refreshPermissionUi()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
