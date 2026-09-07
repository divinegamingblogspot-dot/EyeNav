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
import com.google.mediapipe.framework.image.BitmapImageBuilder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var preview: PreviewView
    private lateinit var cameraFrame: FrameLayout
    private lateinit var targetView: View
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var overlayButton: Button

    private var eyeTracker: EyeTracker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var cameraExecutor: ExecutorService? = null
    private var cameraGeneration = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var calibrationSamples = 0
    private val requiredSamples = 25
    private var calibrationActive = false
    private var gazeSumX = 0f
    private var gazeSumY = 0f
    private var lastCalibrationSampleVersion = -1L
    private var calibrationStartPending = false
    private var destroying = false

    private val calibrationTicker = object : Runnable {
        override fun run() {
            if (calibrationActive && !destroying) {
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
            else showReadyScreen()
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

        cameraFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(18, 18, 24))
        }
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

        root.addView(button("Recalibrate") { startRecalibrationSafely() })
        setContentView(root)
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun showReadyScreen() {
        calibrationActive = false
        targetView.visibility = View.INVISIBLE
        preview.visibility = View.INVISIBLE
        cameraFrame.setBackgroundColor(Color.rgb(18, 18, 24))
        status.text = "Calibration saved. Ready to start EyeNav."
    }

    private fun startRecalibrationSafely() {
        if (calibrationStartPending || destroying) return
        calibrationStartPending = true
        calibrationActive = false
        mainHandler.removeCallbacks(calibrationTicker)
        stopService(Intent(this, EyeNavTrackingService::class.java))
        stopCameraAsync()

        // CameraX releases are asynchronous. Give the tracking service and analyzer time to
        // finish before creating a fresh calibration camera session.
        mainHandler.postDelayed({
            if (destroying) return@postDelayed
            CalibrationManager.reset(this)
            calibrationStartPending = false
            beginCalibration()
        }, 1000L)
    }

    private fun beginCalibration() {
        if (destroying) return
        calibrationActive = true
        calibrationSamples = 0
        gazeSumX = 0f
        gazeSumY = 0f
        lastCalibrationSampleVersion = EyeNavState.sampleVersion
        targetView.visibility = View.VISIBLE
        preview.visibility = View.VISIBLE
        status.text = "Calibration starting..."
        startCalibrationCamera()
    }

    private fun startCalibrationCamera() {
        if (destroying || !calibrationActive) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }

        if (cameraProvider != null && analysis != null) {
            mainHandler.removeCallbacks(calibrationTicker)
            mainHandler.post(calibrationTicker)
            return
        }

        val generation = ++cameraGeneration
        val executor = Executors.newSingleThreadExecutor()
        cameraExecutor = executor
        val tracker = EyeTracker(this)
        eyeTracker = tracker
        status.text = "Preparing camera..."

        // MediaPipe model creation is expensive. Never create it on the UI thread.
        executor.execute {
            try {
                tracker.setup()
            } catch (_: Exception) {
                mainHandler.post {
                    if (!destroying && generation == cameraGeneration) {
                        status.text = "Eye tracker could not start. Tap Recalibrate to retry."
                    }
                }
                return@execute
            }

            mainHandler.post {
                if (destroying || generation != cameraGeneration || !calibrationActive) {
                    executor.execute { runCatching { tracker.close() }; executor.shutdown() }
                    return@post
                }

                val future = ProcessCameraProvider.getInstance(this)
                future.addListener({
                    try {
                        val provider = future.get()
                        if (destroying || generation != cameraGeneration || !calibrationActive) {
                            provider.unbindAll()
                            return@addListener
                        }
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
                        imageAnalysis.setAnalyzer(executor, ImageAnalysis.Analyzer { image ->
                            try {
                                if (!destroying && generation == cameraGeneration && calibrationActive) {
                                    val bitmap = image.toBitmap()
                                    val mpImage = BitmapImageBuilder(bitmap).build()
                                    try {
                                        tracker.processFrame(mpImage)
                                    } finally {
                                        mpImage.close()
                                    }
                                }
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

                        status.text = "Look at the red dot. Keep your head still."
                        mainHandler.removeCallbacks(calibrationTicker)
                        mainHandler.post(calibrationTicker)
                    } catch (_: Exception) {
                        if (!destroying && generation == cameraGeneration) {
                            status.text = "Camera error. Tap Recalibrate to retry."
                        }
                    }
                }, ContextCompat.getMainExecutor(this))
            }
        }
    }

    private fun processCalibrationFrame() {
        if (!calibrationActive || destroying || !EyeNavState.faceDetected) return
        if (preview.width <= 0 || preview.height <= 0) return

        val version = EyeNavState.sampleVersion
        if (version == lastCalibrationSampleVersion) return
        lastCalibrationSampleVersion = version

        val target = CalibrationManager.target()
        targetView.x = target.first * preview.width - targetView.width / 2f
        targetView.y = target.second * preview.height - targetView.height / 2f
        status.text = "Calibration ${CalibrationManager.currentTarget + 1}/9 — look at the red dot"

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
                showReadyScreen()
                refreshPermissionUi()
                // Fully stop the calibration camera after the UI is already in its stable
                // ready state. Teardown happens without blocking the UI on MediaPipe.close().
                stopCameraAsync()
            }
        }
    }

    private fun stopCameraAsync() {
        cameraGeneration++
        mainHandler.removeCallbacks(calibrationTicker)
        calibrationActive = false

        val oldAnalysis = analysis
        analysis = null
        runCatching { oldAnalysis?.clearAnalyzer() }

        val provider = cameraProvider
        cameraProvider = null
        runCatching { provider?.unbindAll() }

        val oldExecutor = cameraExecutor
        cameraExecutor = null
        val tracker = eyeTracker
        eyeTracker = null

        if (oldExecutor != null) {
            oldExecutor.execute {
                runCatching { tracker?.close() }
                oldExecutor.shutdown()
            }
        } else {
            tracker?.close()
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

        stopCameraAsync()
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
        if (CalibrationManager.isCalibrated && !calibrationActive) showReadyScreen()
    }

    override fun onResume() {
        super.onResume()
        if (::accessibilityButton.isInitialized) refreshPermissionUi()
    }

    override fun onDestroy() {
        destroying = true
        mainHandler.removeCallbacksAndMessages(null)
        stopCameraAsync()
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
