package com.prince.eyenav

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
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
    private lateinit var targetView: View
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
            setPadding(24, 24, 24, 18)
            setBackgroundColor(Color.rgb(10, 10, 14))
        }

        root.addView(TextView(this).apply {
            text = "EyeNav"
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, 58))

        root.addView(TextView(this).apply {
            text = "Eye-controlled Android navigation"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, 38))

        val cameraFrame = FrameLayout(this)
        preview = PreviewView(this)
        cameraFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))

        targetView = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.RED)
                setStroke(4, Color.WHITE)
            }
            visibility = View.VISIBLE
        }
        cameraFrame.addView(targetView, FrameLayout.LayoutParams(42, 42))
        root.addView(cameraFrame, LinearLayout.LayoutParams(-1, 0).apply {
            weight = 1f
            topMargin = 12
            bottomMargin = 10
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(8, 6, 8, 6)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, 64))

        accessibilityButton = button("Enable Accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        root.addView(accessibilityButton)

        overlayButton = button("Allow Floating Cursor") {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        root.addView(overlayButton)

        startButton = button("Start EyeNav") { startEyeNav() }
        root.addView(startButton)

        root.addView(button("Recalibrate") {
            CalibrationManager.reset(this)
            calibrationSamples = 0
            startCalibrationCamera()
        })

        setContentView(root)
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun startCalibrationCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }

        calibrationActive = true
        calibrationSamples = 0
        targetView.visibility = View.VISIBLE
        status.text = "Calibration starting..."

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
            cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, previewUseCase, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processCalibrationFrame() {
        if (!calibrationActive || !EyeNavState.faceDetected) return
        if (preview.width <= 0 || preview.height <= 0) return

        val target = CalibrationManager.target()
        targetView.x = target.first * preview.width - targetView.width / 2f
        targetView.y = target.second * preview.height - targetView.height / 2f
        status.text = "Calibration ${CalibrationManager.currentTarget + 1}/9 — keep your head still and look at the red dot"
        calibrationSamples++

        if (calibrationSamples >= requiredSamples) {
            CalibrationManager.addPoint(EyeNavState.gazeX, EyeNavState.gazeY)
            calibrationSamples = 0

            if (CalibrationManager.isCalibrated) {
                calibrationActive = false
                targetView.visibility = View.INVISIBLE
                CalibrationManager.save(this)
                stopCalibrationCamera()
                status.text = "Calibration complete. Enable Accessibility + Floating Cursor, then Start EyeNav."
                refreshPermissionUi()
            }
        }
    }

    private fun stopCalibrationCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        if (::eyeTracker.isInitialized) eyeTracker.close()
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
        ContextCompat.startForegroundService(this, Intent(this, EyeNavTrackingService::class.java))
        Toast.makeText(this, "EyeNav started. Leave this app and use the red cursor.", Toast.LENGTH_LONG).show()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expected = ComponentName(this, EyeNavAccessibilityService::class.java)
        return services.any { info ->
            val service = info.resolveInfo.serviceInfo
            ComponentName(service.packageName, service.name) == expected
        }
    }

    private fun refreshPermissionUi() {
        accessibilityButton.text = if (isAccessibilityEnabled()) "Accessibility: ENABLED" else "Enable Accessibility"
        val overlayGranted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        overlayButton.text = if (overlayGranted) "Floating Cursor: ALLOWED" else "Allow Floating Cursor"
        startButton.isEnabled = CalibrationManager.isCalibrated
        if (CalibrationManager.isCalibrated && !calibrationActive) {
            targetView.visibility = View.INVISIBLE
            status.text = "Calibration saved. Ready for EyeNav."
        }
    }

    override fun onResume() {
        super.onResume()
        if (::accessibilityButton.isInitialized) refreshPermissionUi()
    }

    override fun onDestroy() {
        stopCalibrationCamera()
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
