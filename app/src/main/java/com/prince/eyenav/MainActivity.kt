package com.prince.eyenav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.MediaImageBuilder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView

    private lateinit var cameraExecutor: ExecutorService

    private lateinit var eyeTracker: EyeTracker

    private lateinit var calibrationTarget: TextView

private var calibrationRunning = false

private var calibrationStartTime = 0L

private val calibrationSamplesX =
    mutableListOf<Float>()

private val calibrationSamplesY =
    mutableListOf<Float>()

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor =
            Executors.newSingleThreadExecutor()

        eyeTracker =
            EyeTracker(this)

        eyeTracker.setup()

        createInterface()

        checkCameraPermission()
    }

    private fun createInterface() {

        val container =
            FrameLayout(this)

        previewView =
            PreviewView(this).apply {

                scaleType =
                    PreviewView.ScaleType.FILL_CENTER
            }

        statusText =
            TextView(this).apply {

                text =
                    "EyeNav\n\nStarting camera..."

                textSize = 20f

                setPadding(
                    30,
                    50,
                    30,
                    30
                )
            }

        container.addView(
            previewView
        )

        container.addView(
            statusText
        )

        setContentView(container)
    }

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

            val imageAnalysis =
    ImageAnalysis.Builder()
        .setOutputImageFormat(
            ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
        )
        .setBackpressureStrategy(
            ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        )
        .build()

            imageAnalysis.setAnalyzer(
                cameraExecutor
            ) { imageProxy ->

                analyzeFrame(imageProxy)
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
                updateStatus()
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

    if (EyeNavState.faceDetected) {

        statusText.text =
            "EyeNav\n\n" +
            "FACE DETECTED ✓\n\n" +
            "Landmarks: ${EyeNavState.landmarkCount}\n\n" +
            "LEFT IRIS\n" +
            "X: ${"%.3f".format(EyeNavState.leftIrisX)}\n" +
            "Y: ${"%.3f".format(EyeNavState.leftIrisY)}\n\n" +
            "RIGHT IRIS\n" +
            "X: ${"%.3f".format(EyeNavState.rightIrisX)}\n" +
            "Y: ${"%.3f".format(EyeNavState.rightIrisY)}\n\n" +
            "GAZE\n" +
            "X: ${"%.3f".format(EyeNavState.gazeX)}\n" +
            "Y: ${"%.3f".format(EyeNavState.gazeY)}"
            "Y: ${"%.3f".format(EyeNavState.gazeY)}\n" +
            "Horizontal: ${"%.2f".format(EyeNavState.gazeHorizontal)}\n" +
            "Vertical: ${"%.2f".format(EyeNavState.gazeVertical)}"

    } else {

        statusText.text =
            "EyeNav\n\n" +
            "Looking for your face..."
    }
}

    override fun onDestroy() {

        eyeTracker.close()

        cameraExecutor.shutdown()

        super.onDestroy()
    }
}
