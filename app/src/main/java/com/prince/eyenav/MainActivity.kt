package com.prince.eyenav

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
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
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var gazeCursor: View
    private lateinit var statusText: TextView
    private lateinit var dwellText: TextView
    private lateinit var faceLandmarker: FaceLandmarker

    private var calibrationRunning = true
    private val calibrationSamplesRequired = 50
    private var calibrationSampleCount = 0

    private var smoothedX = 0f
    private var smoothedY = 0f
    private var hasPreviousPosition = false

    private val smoothingFactor = 0.08f

    private var dwellStartTime = 0L
    private var lastDwellX = 0f
    private var lastDwellY = 0f

    private val dwellDuration = 800L
    private val dwellMovementTolerance = 30f

    private val cameraPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Camera permission required",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createUI()
        setupFaceLandmarker()

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(
                Manifest.permission.CAMERA
            )
        }
    }

    private fun createUI() {

        val root = FrameLayout(this)

        previewView = PreviewView(this)

        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        gazeCursor = View(this)

        val cursorSize = 45

        gazeCursor.layoutParams =
            FrameLayout.LayoutParams(
                cursorSize,
                cursorSize
            )

        gazeCursor.background =
            android.graphics.drawable.GradientDrawable().apply {
                shape =
                    android.graphics.drawable.GradientDrawable.OVAL

                setColor(Color.RED)

                setStroke(
                    5,
                    Color.WHITE
                )
            }

        root.addView(gazeCursor)

        statusText = TextView(this)

        statusText.text = "LOOK AT THE DOT"
        statusText.textSize = 20f
        statusText.setTextColor(Color.WHITE)
        statusText.setBackgroundColor(
            Color.argb(170, 0, 0, 0)
        )
        statusText.gravity = Gravity.CENTER

        val statusParams =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )

        statusParams.gravity =
            Gravity.TOP or Gravity.CENTER_HORIZONTAL

        statusParams.topMargin = 50

        root.addView(
            statusText,
            statusParams
        )

        dwellText = TextView(this)

        dwellText.text = "DWELL: 0%"
        dwellText.textSize = 18f
        dwellText.setTextColor(Color.WHITE)
        dwellText.setBackgroundColor(
            Color.argb(170, 0, 0, 0)
        )
        dwellText.gravity = Gravity.CENTER

        val dwellParams =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )

        dwellParams.gravity =
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL

        dwellParams.bottomMargin = 80

        root.addView(
            dwellText,
            dwellParams
        )

        setContentView(root)
    }

    private fun setupFaceLandmarker() {

        val baseOptions =
            com.google.mediapipe.tasks.core.BaseOptions
                .builder()
                .setModelAssetPath("face_landmarker.task")
                .build()

        val options =
            FaceLandmarker.FaceLandmarkerOptions
                .builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result, _ ->
                    processFaceResult(result)
                }
                .build()

        faceLandmarker =
            FaceLandmarker.createFromOptions(
                this,
                options
            )
    }

    private fun startCamera() {

        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({

            val cameraProvider =
                cameraProviderFuture.get()

            val preview =
                Preview.Builder()
                    .build()

            preview.setSurfaceProvider(
                previewView.surfaceProvider
            )

            val imageAnalyzer =
                ImageAnalysis.Builder()
                    .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                    )
                    .setOutputImageFormat(
                        ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                    )
                    .build()

            imageAnalyzer.setAnalyzer(
                ContextCompat.getMainExecutor(this)
            ) { imageProxy ->

                try {

                    val bitmap =
                        imageProxy.toBitmap()

                    val mpImage =
                        BitmapImageBuilder(bitmap)
                            .build()

                    val timestamp =
                        SystemClock.uptimeMillis()

                    faceLandmarker.detectAsync(
                        mpImage,
                        timestamp
                    )

                } catch (_: Exception) {

                } finally {

                    imageProxy.close()
                }
            }

            val cameraSelector =
                CameraSelector.DEFAULT_FRONT_CAMERA

            cameraProvider.unbindAll()

            cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFaceResult(
        result: FaceLandmarkerResult
    ) {

        if (result.faceLandmarks().isEmpty()) {
            return
        }

        val landmarks =
            result.faceLandmarks()[0]

        if (landmarks.size < 478) {
            return
        }

        val leftEye =
            landmarks[468]

        val rightEye =
            landmarks[473]

        val gazeX =
            (leftEye.x() + rightEye.x()) / 2f

        val gazeY =
            (leftEye.y() + rightEye.y()) / 2f

        runOnUiThread {

            if (calibrationRunning) {

                processCalibration(
                    gazeX,
                    gazeY
                )

            } else {

                processGaze(
                    gazeX,
                    gazeY
                )
            }
        }
    }

    private fun processCalibration(
        gazeX: Float,
        gazeY: Float
    ) {

        val target =
            CalibrationManager.target()

        statusText.text =
            "LOOK AT THE DOT ${CalibrationManager.currentTarget + 1}/9"

        moveCalibrationDot(
            target.first * previewView.width,
            target.second * previewView.height
        )

        calibrationSampleCount++

        if (
            calibrationSampleCount >=
            calibrationSamplesRequired
        ) {

            CalibrationManager.addPoint(
                gazeX,
                gazeY,
                previewView.width.toFloat(),
                previewView.height.toFloat()
            )

            calibrationSampleCount = 0

            if (
                CalibrationManager.isCalibrated
            ) {

                calibrationRunning = false

                statusText.text =
                    "EYE TRACKING ACTIVE"

                dwellText.text =
                    "DWELL: 0%"

                smoothedX =
                    previewView.width / 2f

                smoothedY =
                    previewView.height / 2f

                hasPreviousPosition = false
            }
        }
    }

    private fun moveCalibrationDot(
        x: Float,
        y: Float
    ) {

        gazeCursor.x =
            x - gazeCursor.width / 2f

        gazeCursor.y =
            y - gazeCursor.height / 2f
    }

    private fun processGaze(
        gazeX: Float,
        gazeY: Float
    ) {

        val position =
            CalibrationManager.screenPosition(
                gazeX,
                gazeY,
                previewView.width.toFloat(),
                previewView.height.toFloat()
            )

        val targetX =
            position.first

        val targetY =
            position.second

        if (!hasPreviousPosition) {

            smoothedX = targetX
            smoothedY = targetY

            hasPreviousPosition = true

        } else {

            smoothedX +=
                (targetX - smoothedX) *
                    smoothingFactor

            smoothedY +=
                (targetY - smoothedY) *
                    smoothingFactor
        }

        gazeCursor.x =
            smoothedX -
                gazeCursor.width / 2f

        gazeCursor.y =
            smoothedY -
                gazeCursor.height / 2f

        processDwell(
            smoothedX,
            smoothedY
        )
    }

    private fun processDwell(
        x: Float,
        y: Float
    ) {

        if (dwellStartTime == 0L) {

            dwellStartTime =
                SystemClock.uptimeMillis()

            lastDwellX = x
            lastDwellY = y

            dwellText.text =
                "DWELL: 0%"

            return
        }

        val movement =
            abs(x - lastDwellX) +
                abs(y - lastDwellY)

        if (
            movement >
            dwellMovementTolerance
        ) {

            dwellStartTime =
                SystemClock.uptimeMillis()

            lastDwellX = x
            lastDwellY = y

            dwellText.text =
                "DWELL: 0%"

            return
        }

        val elapsed =
            SystemClock.uptimeMillis() -
                dwellStartTime

        val progress =
            (
                elapsed.toFloat() /
                    dwellDuration.toFloat()
                ).coerceIn(
                    0f,
                    1f
                )

        dwellText.text =
            "DWELL: ${(progress * 100).toInt()}%"

        if (
            elapsed >= dwellDuration
        ) {

            performEyeClick()

            dwellStartTime = 0L

            dwellText.text =
                "DWELL: 0%"
        }
    }

    private fun performEyeClick() {

        EyeNavAccessibilityService.instance?.performEyeClick(
            smoothedX,
            smoothedY
        )
    }

    override fun onDestroy() {

        super.onDestroy()

        if (::faceLandmarker.isInitialized) {
            faceLandmarker.close()
        }
    }
}
