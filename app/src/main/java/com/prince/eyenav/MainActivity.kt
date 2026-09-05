package com.prince.eyenav

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.MediaImageBuilder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var eyeTracker: EyeTracker

    private lateinit var calibrationTarget: TextView
    private lateinit var gazeCursor: TextView
    private lateinit var dwellProgress: TextView

    private var calibrationRunning = false
    private var calibrationStartTime = 0L

    private val calibrationSamplesX =
        mutableListOf<Float>()

    private val calibrationSamplesY =
        mutableListOf<Float>()

    // -----------------------------
    // SMOOTHING
    // -----------------------------

    private var smoothX = 0f
    private var smoothY = 0f

    private val smoothingFactor = 0.18f

    private var cursorInitialized = false

    // -----------------------------
    // DWELL CLICK
    // -----------------------------

    private var dwellStartTime = 0L

    private var dwellTriggered = false

    private val dwellDuration =
        800L

    private val dwellMovementTolerance =
        45f

    private var lastDwellX = 0f
    private var lastDwellY = 0f

    // -----------------------------
    // CAMERA PERMISSION
    // -----------------------------

    private val cameraPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {

                startCamera()

            } else {

                Toast.makeText(
                    this,
                    "Camera permission is required for EyeNav",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        cameraExecutor =
            Executors.newSingleThreadExecutor()

        eyeTracker =
            EyeTracker(this)

        eyeTracker.setup()

        createInterface()

        checkCameraPermission()
    }

    // =========================================================
    // UI
    // =========================================================

    private fun createInterface() {

        val container =
            FrameLayout(this)

        // CAMERA PREVIEW
        previewView =
            PreviewView(this).apply {

                scaleType =
                    PreviewView.ScaleType.FILL_CENTER

                implementationMode =
                    PreviewView.ImplementationMode.COMPATIBLE
            }

        container.addView(
            previewView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // STATUS
        statusText =
            TextView(this).apply {

                text =
                    "EyeNav\n\nStarting camera..."

                textSize = 18f

                setTextColor(Color.WHITE)

                setPadding(
                    30,
                    50,
                    30,
                    30
                )

                elevation = 10f
            }

        container.addView(
            statusText
        )

        // CALIBRATION TARGET
        calibrationTarget =
            TextView(this).apply {

                text = "●"

                textSize = 50f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    Color.WHITE
                )

                visibility =
                    TextView.GONE

                elevation = 100f
            }

        val targetParams =
            FrameLayout.LayoutParams(
                90,
                90
            )

        container.addView(
            calibrationTarget,
            targetParams
        )

        // GAZE CURSOR
        gazeCursor =
            TextView(this).apply {

                text = ""

                visibility =
                    TextView.GONE

                background =
                    GradientDrawable().apply {

                        shape =
                            GradientDrawable.OVAL

                        setColor(
                            Color.RED
                        )

                        setStroke(
                            5,
                            Color.WHITE
                        )
                    }

                elevation = 1000f
            }

        val cursorParams =
            FrameLayout.LayoutParams(
                70,
                70
            )

        container.addView(
            gazeCursor,
            cursorParams
        )

        // DWELL PROGRESS
        dwellProgress =
            TextView(this).apply {

                text = ""

                textSize = 14f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    Color.WHITE
                )

                visibility =
                    TextView.GONE

                elevation = 1001f
            }

        val dwellParams =
            FrameLayout.LayoutParams(
                100,
                50
            )

        container.addView(
            dwellProgress,
            dwellParams
        )

        // CALIBRATION BUTTON
        val calibrationButton =
            Button(this).apply {

                text =
                    "START CALIBRATION"

                setOnClickListener {

                    startCalibration()
                }
            }

        val buttonParams =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )

        buttonParams.gravity =
            Gravity.BOTTOM or
            Gravity.CENTER_HORIZONTAL

        buttonParams.bottomMargin =
            80

        container.addView(
            calibrationButton,
            buttonParams
        )

        setContentView(container)
    }

    // =========================================================
    // CAMERA PERMISSION
    // =========================================================

    private fun checkCameraPermission() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) ==
            PackageManager.PERMISSION_GRANTED
        ) {

            startCamera()

        } else {

            cameraPermissionLauncher.launch(
                Manifest.permission.CAMERA
            )
        }
    }

    // =========================================================
    // CAMERA
    // =========================================================

    private fun startCamera() {

        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(
                this
            )

        cameraProviderFuture.addListener({

            val cameraProvider =
                cameraProviderFuture.get()

            val preview =
                Preview.Builder()
                    .build()

            preview.setSurfaceProvider(
                previewView.surfaceProvider
            )

            val imageAnalysis =
                ImageAnalysis.Builder()

                    .setOutputImageFormat(
                        ImageAnalysis
                            .OUTPUT_IMAGE_FORMAT_RGBA_8888
                    )

                    .setBackpressureStrategy(
                        ImageAnalysis
                            .STRATEGY_KEEP_ONLY_LATEST
                    )

                    .build()

            imageAnalysis.setAnalyzer(
                cameraExecutor
            ) { imageProxy ->

                analyzeFrame(
                    imageProxy
                )
            }

            val cameraSelector =
                CameraSelector.DEFAULT_FRONT_CAMERA

            try {

                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

            } catch (exception: Exception) {

                runOnUiThread {

                    statusText.text =
                        "EyeNav\n\n" +
                        "Camera error:\n" +
                        exception.message
                }
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // =========================================================
    // FRAME PROCESSING
    // =========================================================

    private fun analyzeFrame(
        imageProxy: ImageProxy
    ) {

        try {

            val mediaImage =
                imageProxy.image

            if (mediaImage == null) {
                return
            }

            val mpImage =
                MediaImageBuilder(
                    mediaImage
                ).build()

            eyeTracker.processFrame(
                mpImage,
                imageProxy.imageInfo.timestamp
            )

            runOnUiThread {

                if (calibrationRunning) {

                    processCalibration()

                } else {

                    updateStatus()

                    if (
                        CalibrationManager.isCalibrated &&
                        EyeNavState.faceDetected
                    ) {

                        updateGazeCursor()

                        processDwell()
                    }
                }
            }

        } catch (exception: Exception) {

            runOnUiThread {

                statusText.text =
                    "EyeNav\n\n" +
                    "Error:\n" +
                    exception.message
            }

        } finally {

            imageProxy.close()
        }
    }

    // =========================================================
    // STATUS
    // =========================================================

    private fun updateStatus() {

        val error =
            EyeNavState.errorMessage

        if (error != null) {

            statusText.text =
                "EyeNav\n\n" +
                "MediaPipe Error:\n" +
                error

            return
        }

        if (
            EyeNavState.faceDetected
        ) {

            statusText.text =
                "EyeNav\n\n" +
                "FACE DETECTED\n\n" +
                "Landmarks: " +
                EyeNavState.landmarkCount +
                "\n\n" +

                "GAZE\n" +
                "X: " +
                "%.3f".format(
                    EyeNavState.gazeX
                ) +
                "\n" +

                "Y: " +
                "%.3f".format(
                    EyeNavState.gazeY
                ) +
                "\n\n" +

                "Horizontal: " +
                "%.2f".format(
                    EyeNavState.gazeHorizontal
                ) +
                "\n" +

                "Vertical: " +
                "%.2f".format(
                    EyeNavState.gazeVertical
                )

        } else {

            statusText.text =
                "EyeNav\n\n" +
                "Looking for your face..."
        }
    }

    // =========================================================
    // CALIBRATION START
    // =========================================================

    private fun startCalibration() {

        CalibrationManager.reset()

        calibrationRunning =
            true

        calibrationStartTime =
            SystemClock.uptimeMillis()

        calibrationSamplesX.clear()
        calibrationSamplesY.clear()

        cursorInitialized =
            false

        dwellStartTime =
            0L

        dwellTriggered =
            false

        gazeCursor.visibility =
            TextView.GONE

        dwellProgress.visibility =
            TextView.GONE

        calibrationTarget.visibility =
            TextView.VISIBLE

        statusText.text =
            "CALIBRATION\n\n" +
            "Look directly at the dot\n\n" +
            "Keep your head still"

        previewView.post {

            positionCalibrationTarget()
        }
    }

    // =========================================================
    // CALIBRATION TARGET POSITION
    // =========================================================

    private fun positionCalibrationTarget() {

        val position =
            CalibrationManager.target()

        val width =
            previewView.width

        val height =
            previewView.height

        if (
            width <= 0 ||
            height <= 0
        ) {
            return
        }

        val x =
            position.first * width

        val y =
            position.second * height

        val params =
            calibrationTarget.layoutParams
                as FrameLayout.LayoutParams

        params.leftMargin =
            x.toInt() - 45

        params.topMargin =
            y.toInt() - 45

        calibrationTarget.layoutParams =
            params

        calibrationTarget.bringToFront()
    }

    // =========================================================
    // CALIBRATION PROCESS
    // =========================================================

    private fun processCalibration() {

        if (!calibrationRunning) {
            return
        }

        if (
            !EyeNavState.faceDetected
        ) {

            statusText.text =
                "CALIBRATION\n\n" +
                "Face not detected"

            return
        }

        val elapsed =
            SystemClock.uptimeMillis() -
            calibrationStartTime

        if (elapsed < 1000) {

            statusText.text =
                "CALIBRATION\n\n" +
                "Look at the dot..."

            return
        }

        calibrationSamplesX.add(
            EyeNavState.gazeX
        )

        calibrationSamplesY.add(
            EyeNavState.gazeY
        )

        statusText.text =
            "CALIBRATION\n\n" +
            "Point " +
            (CalibrationManager.currentTarget + 1) +
            " / 9"

        // 30 samples per point
        if (
            calibrationSamplesX.size >= 30
        ) {

            val averageX =
                calibrationSamplesX
                    .average()
                    .toFloat()

            val averageY =
                calibrationSamplesY
                    .average()
                    .toFloat()

            CalibrationManager.addPoint(
                averageX,
                averageY,
                previewView.width.toFloat(),
                previewView.height.toFloat()
            )

            calibrationSamplesX.clear()
            calibrationSamplesY.clear()

            if (
                CalibrationManager.isCalibrated
            ) {

                calibrationRunning =
                    false

                calibrationTarget.visibility =
                    TextView.GONE

                gazeCursor.visibility =
                    TextView.VISIBLE

                dwellProgress.visibility =
                    TextView.VISIBLE

                cursorInitialized =
                    false

                dwellStartTime =
                    0L

                dwellTriggered =
                    false

                statusText.text =
                    "CALIBRATION COMPLETE\n\n" +
                    "Move your eyes.\n\n" +
                    "RED CIRCLE = GAZE CURSOR"

                gazeCursor.bringToFront()

                dwellProgress.bringToFront()

            } else {

                calibrationStartTime =
                    SystemClock.uptimeMillis()

                previewView.post {

                    positionCalibrationTarget()
                }
            }
        }
    }

    // =========================================================
    // GAZE CURSOR
    // =========================================================

    private fun updateGazeCursor() {

        if (
            previewView.width <= 0 ||
            previewView.height <= 0
        ) {
            return
        }

        val position =
            CalibrationManager.screenPosition(
                EyeNavState.gazeX,
                EyeNavState.gazeY,
                previewView.width.toFloat(),
                previewView.height.toFloat()
            )

        val targetX =
            position.first

        val targetY =
            position.second

        // FIRST POSITION
        if (!cursorInitialized) {

            smoothX =
                targetX

            smoothY =
                targetY

            cursorInitialized =
                true

        } else {

            // SMOOTH MOVEMENT
            smoothX +=
                (targetX - smoothX) *
                smoothingFactor

            smoothY +=
                (targetY - smoothY) *
                smoothingFactor
        }

        val cursorParams =
            gazeCursor.layoutParams
                as FrameLayout.LayoutParams

        cursorParams.leftMargin =
            smoothX.toInt() - 35

        cursorParams.topMargin =
            smoothY.toInt() - 35

        gazeCursor.layoutParams =
            cursorParams

        gazeCursor.bringToFront()

        // Put dwell text underneath cursor
        val dwellParams =
            dwellProgress.layoutParams
                as FrameLayout.LayoutParams

        dwellParams.leftMargin =
            smoothX.toInt() - 50

        dwellParams.topMargin =
            smoothY.toInt() + 45

        dwellProgress.layoutParams =
            dwellParams

        dwellProgress.bringToFront()
    }

    // =========================================================
    // DWELL CLICK
    // =========================================================

    private fun processDwell() {

        if (!cursorInitialized) {
            return
        }

        val x =
            smoothX

        val y =
            smoothY

        // First position
        if (
            dwellStartTime == 0L
        ) {

            dwellStartTime =
                SystemClock.uptimeMillis()

            lastDwellX =
                x

            lastDwellY =
                y

            dwellTriggered =
                false

            return
        }

        val movementX =
            abs(
                x - lastDwellX
            )

        val movementY =
            abs(
                y - lastDwellY
            )

        // Eye moved too much
        if (
            movementX >
            dwellMovementTolerance ||
            movementY >
            dwellMovementTolerance
        ) {

            dwellStartTime =
                SystemClock.uptimeMillis()

            lastDwellX =
                x

            lastDwellY =
                y

            dwellTriggered =
                false

            dwellProgress.text =
                ""

            return
        }

        val elapsed =
            SystemClock.uptimeMillis() -
            dwellStartTime

        val percent =
            (
                elapsed.toFloat() /
                dwellDuration.toFloat()
            )
                .coerceIn(
                    0f,
                    1f
                )

        dwellProgress.text =
            "${(percent * 100).toInt()}%"

        // DWELL COMPLETE
        if (
            elapsed >= dwellDuration &&
            !dwellTriggered
        ) {

            dwellTriggered =
                true

            performEyeClick()

            dwellStartTime =
                SystemClock.uptimeMillis()

            dwellProgress.text =
                "CLICK"
        }
    }

    // =========================================================
    // EYE CLICK
    // =========================================================

    private fun performEyeClick() {

        Toast.makeText(
            this,
            "EYE CLICK",
            Toast.LENGTH_SHORT
        ).show()

        statusText.text =
            "EyeNav\n\n" +
            "EYE CLICK\n\n" +
            "Gaze cursor activated"
    }

    // =========================================================
    // DESTROY
    // =========================================================

    override fun onDestroy() {

        eyeTracker.close()

        cameraExecutor.shutdown()

        super.onDestroy()
    }
}
