package com.prince.eyenav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class EyeNavTrackingService : LifecycleService() {

    companion object {
        const val ACTION_STOP = "com.prince.eyenav.STOP"
        private const val CHANNEL_ID = "eyenav_tracking"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var eyeTracker: EyeTracker
    private lateinit var overlay: EyeNavOverlay
    private val handler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var cameraExecutor: ExecutorService? = null

    private var smoothedX = 0f
    private var smoothedY = 0f
    private var initialized = false
    private var dwellStart = 0L
    private var dwellX = 0f
    private var dwellY = 0f
    private var lastClick = 0L
    private var clickArmed = true

    private val smoothing = 0.18f
    private val dwellDuration = 1400L
    private val dwellTolerance = 20f
    private val clickCooldown = 1200L
    private val rearmDistance = 70f

    private val ticker = object : Runnable {
        override fun run() {
            updateCursorAndDwell()
            handler.postDelayed(this, 33L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())

        CalibrationManager.load(this)
        eyeTracker = EyeTracker(this)
        eyeTracker.setup()
        overlay = EyeNavOverlay(this)
        overlay.show()
        handler.post(ticker)
        startCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                cameraExecutor = Executors.newSingleThreadExecutor()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                analysis = imageAnalysis
                imageAnalysis.setAnalyzer(cameraExecutor!!, ImageAnalysis.Analyzer { image ->
                    try {
                        val bitmap = image.toBitmap()
                        val mpImage = BitmapImageBuilder(bitmap).build()
                        eyeTracker.processFrame(mpImage, System.nanoTime() / 1_000_000L)
                    } catch (_: Exception) {
                        // Drop malformed frames; never let one frame kill the analyzer.
                    } finally {
                        image.close()
                    }
                })

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    imageAnalysis
                )
            } catch (_: Exception) {
                // Service remains alive; a later restart can recreate the camera pipeline.
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateCursorAndDwell() {
        if (!EyeNavState.faceDetected) {
            dwellStart = 0L
            initialized = false
            return
        }

        val display = resources.displayMetrics
        val position = CalibrationManager.screenPosition(
            EyeNavState.gazeX,
            EyeNavState.gazeY,
            display.widthPixels.toFloat(),
            display.heightPixels.toFloat()
        )

        val targetX = position.first.coerceIn(0f, display.widthPixels.toFloat())
        val targetY = position.second.coerceIn(0f, display.heightPixels.toFloat())

        if (!initialized) {
            smoothedX = targetX
            smoothedY = targetY
            initialized = true
            dwellStart = 0L
        } else {
            smoothedX += (targetX - smoothedX) * smoothing
            smoothedY += (targetY - smoothedY) * smoothing
        }

        overlay.moveTo(smoothedX, smoothedY)
        processDwell(smoothedX, smoothedY)
    }

    private fun processDwell(x: Float, y: Float) {
        val now = System.currentTimeMillis()

        if (!clickArmed) {
            val distanceFromClickPoint = abs(x - dwellX) + abs(y - dwellY)
            if (distanceFromClickPoint >= rearmDistance && now - lastClick >= clickCooldown) {
                clickArmed = true
                dwellStart = now
                dwellX = x
                dwellY = y
            }
            return
        }

        if (dwellStart == 0L) {
            dwellStart = now
            dwellX = x
            dwellY = y
            return
        }

        val movement = abs(x - dwellX) + abs(y - dwellY)
        if (movement > dwellTolerance) {
            dwellStart = now
            dwellX = x
            dwellY = y
            return
        }

        if (now - dwellStart >= dwellDuration && now - lastClick >= clickCooldown) {
            EyeNavAccessibilityService.instance?.performEyeClick(x, y)
            lastClick = now
            clickArmed = false
            dwellStart = 0L
            dwellX = x
            dwellY = y
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "EyeNav eye tracking",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EyeNav is active")
            .setContentText("Eye tracking and system cursor are running")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return Service.START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        analysis?.clearAnalyzer()
        analysis = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        cameraExecutor?.shutdownNow()
        cameraExecutor = null
        if (::eyeTracker.isInitialized) eyeTracker.close()
        if (::overlay.isInitialized) overlay.remove()
        super.onDestroy()
    }
}
