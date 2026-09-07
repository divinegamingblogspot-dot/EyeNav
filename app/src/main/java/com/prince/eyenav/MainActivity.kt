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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Size
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var preview: PreviewView
    private lateinit var targetView: View
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var overlayButton: Button

    private lateinit var eyeTracker: EyeTracker
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var cameraExecutor: ExecutorService? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var calibrationSamples = 0
    private val requiredSamples = 25
    private var calibrationActive = false
    private var gazeSumX = 0f
    private var gazeSumY = 0f
    private var lastCalibrationSampleVersion = -1L
    private var calibrationStartPending = false

    private val calibrationTicker = object : Runnable {
        override fun run() {
            if (calibrationActive) {
                processCalibrationFrame()
                mainHandler.postDelayed(this, 40L)
            }
        }
    }

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
            // Never let MainActivity and the foreground tracking service own the camera together.
            // That race was the cause of the black/frozen preview during recalibration.
            stopTrackingServiceForCalibration()
        })

        setContentView(root)
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun stopTrackingServiceForCalibration() {
        if (calibrationStartPending) return
        calibrationStartPending = true
        calibrationActive = false
        mainHandler.removeCallbacks(calibrationTicker)

        stopService(Intent(this, EyeNavTrackingService::class.java))
        stopCalibrationCamera()

        // Give CameraX/MediaPipe a moment to release the old camera before opening it again.
        mainHandler.postDelayed({
            calibrationStartPending = false
            CalibrationManager.reset(this)
            beginCalibration()
        }, 350L)
    }

    private fun beginCalibration() {
        calibrationActive = true
        calibrationSamples = 0
        gazeSumX = 0f
        gazeSumY = 0f
        lastCalibrationSampleVersion = EyeNavState.sampleVersion
        targetView.visibility = View.VISIBLE
        status.text = "Calibration starting..."

        if (cameraProvider != null && ::eyeTracker.isInitialized) {
            mainHandler.removeCallbacks(calibrationTicker)
            mainHandler.post(calibrationTicker)
            return
        }

        startCalibrationCamera()
    }

    private fun startCalibrationCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }

        if (cameraProvider != null && ::eyeTracker.isInitialized) {
            mainHandler.removeCallbacks(calibrationTicker)
            mainHandler.post(calibrationTicker)
            return
        }

        calibrationActive = true
        calibrationSamples = 0
        gazeSumX = 0f
        gazeSumY = 0f
        lastCalibrationSampleVersion = EyeNavState.sampleVersion
        targetView.visibility = View.VISIBLE
        status.text = "Calibration starting..."

        eyeTracker = EyeTracker(this)
        eyeTracker.setup()
        cameraExecutor = Executors.newSingleThreadExecutor()

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val previewUseCase = Preview.Builder().build().also {
                    it.setSurfaceProvider(preview.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                analysis = imageAnalysis
                imageAnalysis.setAnalyzer(cameraExecutor!!, ImageAnalysis.Analyzer { image ->
                    try {
                        val bitmap = image.toBitmap()
                        val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
                        eyeTracker.processFrame(mpImage, System.nanoTime() / 1_000_000L)
                    } catch (_: Exception) {
                    } finally {
                        image.close()
                    }
                })

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    previewUseCase,
                    imageAnalysis
                )

                mainHandler.removeCallbacks(calibrationTicker)
                mainHandler.post(calibrationTicker)
            } catch (_: Exception) {
                status.text = "Camera error. Please try again."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processCalibrationFrame() {
        if (!calibrationActive || !EyeNavState.faceDetected) return
        if (preview.width <= 0 || preview.height <= 0) return

        val version = EyeNavState.sampleVersion
        if (version == lastCalibrationSampleVersion) return
        lastCalibrationSampleVersion = version

        val target = CalibrationManager.target()
        targetView.x = target.first * preview.width - targetView.width / 2f
        targetView.y = target.second * preview.height - targetView.height / 2f
        status.text = "Calibration ${CalibrationManager.currentTarget + 1}/9 — keep your head still and look at the red dot"

        gazeSumX += EyeNavState.gazeX
        gazeSumY += EyeNavState.gazeY
        calibrationSamples++

        if (calibrationSamples >= requiredSamples) {
            val averageX = (gazeSumX / calibrationSamples).coerceIn(0.01f, 0.99f)
            val averageY = (gazeSumY / calibrationSamples).coerceIn(0.01f, 0.99f)
            CalibrationManager.addPoint(averageX, averageY)
            calibrationSamples = 0
            gazeSumX = 0f
            gazeSumY = 0f

            if (CalibrationManager.isCalibrated) {
                calibrationActive = false
                mainHandler.removeCallbacks(calibrationTicker)
                targetView.visibility = View.INVISIBLE
                CalibrationManager.save(this)
                stopCalibrationCamera()
                status.text = "Calibration complete. Enable Accessibility + Floating Cursor, then Start EyeNav."
                refreshPermissionUi()
            }
        }
    }

    private fun stopCalibrationCamera() {
        calibrationActive = false
        mainHandler.removeCallbacks(calibrationTicker)
        analysis?.clearAnalyzer()
        analysis = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        cameraExecutor?.shutdownNow()
        cameraExecutor = null
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
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
