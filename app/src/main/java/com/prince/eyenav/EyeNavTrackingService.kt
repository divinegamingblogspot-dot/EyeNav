package com.prince.eyenav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
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

    private var smoothedX = 0f
    private var smoothedY = 0f
    private var initialized = false
    private var dwellStart = 0L
    private var dwellX = 0f
    private var dwellY = 0f
    private var lastClick = 0L

    private val smoothing = 0.10f
    private val dwellDuration = 800L
    private val dwellTolerance = 28f
    private val clickCooldown = 900L

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

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { image ->
                    try {
                        val bitmap: Bitmap = image.toBitmap()
                        val mpImage = BitmapImageBuilder(bitmap).build()
                        eyeTracker.processFrame(mpImage, System.currentTimeMillis())
                    } catch (_: Exception) {
                    } finally {
                        image.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis
                )
            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateCursorAndDwell() {
        if (!EyeNavState.faceDetected) return

        val display = resources.displayMetrics
        val position = CalibrationManager.screenPosition(
            EyeNavState.gazeX,
            EyeNavState.gazeY,
            display.widthPixels.toFloat(),
            display.heightPixels.toFloat()
        )

        val targetX = position.first
        val targetY = position.second

        if (!initialized) {
            smoothedX = targetX
            smoothedY = targetY
            initialized = true
        } else {
            smoothedX += (targetX - smoothedX) * smoothing
            smoothedY += (targetY - smoothedY) * smoothing
        }

        overlay.moveTo(smoothedX, smoothedY)
        processDwell(smoothedX, smoothedY)
    }

    private fun processDwell(x: Float, y: Float) {
        val now = System.currentTimeMillis()

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
            dwellStart = 0L
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
        cameraProvider?.unbindAll()
        if (::eyeTracker.isInitialized) eyeTracker.close()
        if (::overlay.isInitialized) overlay.remove()
        super.onDestroy()
    }
}
