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

    private var eyeTracker: EyeTracker? = null
    private var overlay: EyeNavOverlay? = null
    private val handler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var cameraExecutor: ExecutorService? = null
    private var stopping = false
    private var cameraStarted = false

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
            if (!stopping) {
                updateCursorAndDwell()
                handler.postDelayed(this, 33L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        stopping = false
        cameraStarted = false

        // Calibration leaves the last valid gaze sample in memory. Never let the live cursor
        // start from that stale point (often the last calibration target, including the bottom
        // edge). Live tracking must begin from "no face" until a fresh camera frame arrives.
        EyeNavState.reset()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())

        CalibrationManager.load(this)
        val executor = Executors.newSingleThreadExecutor()
        cameraExecutor = executor
        val tracker = EyeTracker(this)
        eyeTracker = tracker
        val cursor = EyeNavOverlay(this)
        overlay = cursor
        cursor.show()
        handler.post(ticker)

        // Model creation is deliberately off the main thread. Camera binding starts only
        // after MediaPipe is ready, so there is no half-initialized analyzer.
        executor.execute {
            try {
                tracker.setup()
            } catch (_: Exception) {
                if (!stopping) handler.post { stopSelf() }
                return@execute
            }
            if (!stopping) handler.post { startCamera(tracker, executor) }
        }
    }

    private fun startCamera(tracker: EyeTracker, executor: ExecutorService) {
        if (stopping || cameraStarted) return
        cameraStarted = true

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                if (stopping) return@addListener
                val provider = future.get()
                cameraProvider = provider

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                analysis = imageAnalysis
                imageAnalysis.setAnalyzer(executor, ImageAnalysis.Analyzer { image ->
                    try {
                        if (!stopping) {
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
                    imageAnalysis
                )
            } catch (_: Exception) {
                cameraStarted = false
                if (!stopping) stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateCursorAndDwell() {
        // Do not move the cursor until this service has received a fresh face/iris result.
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

        overlay?.moveTo(smoothedX, smoothedY)
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
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // EyeNav is explicitly started by the user. Do not resurrect an old camera service
        // after Android kills the process; a fresh user start creates a clean pipeline.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        cameraStarted = false
        handler.removeCallbacksAndMessages(null)
        EyeNavState.reset()

        val oldAnalysis = analysis
        analysis = null
        runCatching { oldAnalysis?.clearAnalyzer() }

        val provider = cameraProvider
        cameraProvider = null
        runCatching { provider?.unbindAll() }

        val executor = cameraExecutor
        cameraExecutor = null
        val tracker = eyeTracker
        eyeTracker = null
        val cursor = overlay
        overlay = null

        // Do not call MediaPipe.close() on the service main thread. The analyzer executor
        // owns the tracker, so closing it after queued frames finish avoids shutdown freezes.
        if (executor != null) {
            runCatching {
                executor.execute {
                    runCatching { tracker?.close() }
                    executor.shutdown()
                }
            }
        } else {
            tracker?.close()
        }
        cursor?.remove()
        super.onDestroy()
    }
}
