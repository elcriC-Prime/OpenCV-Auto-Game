package com.battlecats.autobot

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.util.concurrent.atomic.AtomicBoolean

class BotForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.battlecats.autobot.ACTION_START"
        const val ACTION_STOP = "com.battlecats.autobot.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "battle_cats_bot_channel"
        private const val NOTIF_ID = 1001

        @Volatile
        var isServiceRunning: Boolean = false
            private set
    }

    private lateinit var config: BotConfig
    private lateinit var overlay: OverlayManager
    private lateinit var matcher: ScreenMatcher
    private lateinit var wm: WindowManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureWidth = 0
    private var captureHeight = 0

    private val botActive = AtomicBoolean(false)
    private val serviceAlive = AtomicBoolean(true)
    private var workerThread: Thread? = null

    private val frameLock = Any()
    private var latestRgbaMat: Mat? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        config = BotConfig(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        matcher = ScreenMatcher(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, data)
        if (projection == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                botActive.set(false)
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        setupVirtualDisplay()

        if (!::overlay.isInitialized) {
            overlay = OverlayManager(
                context = this,
                config = config,
                onToggleRun = { running ->
                    botActive.set(running)
                    if (running) {
                        overlay.updateStatus("運行中...")
                    }
                },
                onCloseRequested = {
                    stopSelf()
                }
            )
            overlay.show()
        }

        startWorkerLoop()
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setupVirtualDisplay()
        matcher.resetScaleLock()
        if (::overlay.isInitialized) {
            overlay.syncMarkersToScreenSize()
        }
    }

    private fun currentDisplaySize(): Triple<Int, Int, Int> {
        val dpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            @Suppress("DEPRECATION")
            val dm = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    private fun setupVirtualDisplay() {
        val (w, h, dpi) = currentDisplaySize()
        if (w == captureWidth && h == captureHeight && virtualDisplay != null) return
        captureWidth = w
        captureHeight = h

        virtualDisplay?.release()
        imageReader?.close()

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "BattleCatsCapture",
            w,
            h,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )
    }

    /**
     * Updates [latestRgbaMat] in place when a new ImageReader frame is available.
     * Returns true if a brand-new frame arrived from the virtual display.
     */
    private fun refreshLatestFrame(): Boolean {
        val reader = imageReader ?: return false
        val image = reader.acquireLatestImage() ?: return false
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowCols = rowStride / pixelStride
            val full = Mat(image.height, rowCols, CvType.CV_8UC4, buffer)
            val cropped = full.submat(0, image.height, 0, image.width)
            try {
                synchronized(frameLock) {
                    val dst = latestRgbaMat ?: Mat().also { latestRgbaMat = it }
                    cropped.copyTo(dst)
                }
            } finally {
                cropped.release()
                full.release()
            }
            return true
        } finally {
            image.close()
        }
    }

    private fun startWorkerLoop() {
        if (workerThread?.isAlive == true) return
        workerThread = Thread({
            var lastBattleClickMs = 0L
            var battlePointIdx = 0
            var lastState = GameState.UNKNOWN

            while (serviceAlive.get()) {
                if (!botActive.get()) {
                    lastBattleClickMs = 0L
                    battlePointIdx = 0
                    lastState = GameState.UNKNOWN
                    SystemClock.sleep(150)
                    continue
                }

                val acc = BotAccessibilityService.instance
                if (acc == null) {
                    overlay.updateStatus("請先開啟無障礙服務")
                    SystemClock.sleep(300)
                    continue
                }

                val hasNewFrame = refreshLatestFrame()
                val frame = synchronized(frameLock) { latestRgbaMat }
                if (frame == null || frame.empty()) {
                    SystemClock.sleep(60)
                    continue
                }

                // If screen didn't redraw since last UNKNOWN frame and scale is locked, skip redundant OpenCV pass
                val det = if (!hasNewFrame && lastState == GameState.UNKNOWN && matcher.isIdleAtLockedScale()) {
                    Detection(GameState.UNKNOWN, null, null, null)
                } else {
                    synchronized(frameLock) {
                        matcher.detect(
                            frame,
                            threshold = config.matchThreshold.toDouble(),
                            brightnessMin = config.brightnessRatioMin.toDouble(),
                        )
                    }
                }
                lastState = det.state

                if (det.state != GameState.IN_BATTLE) {
                    lastBattleClickMs = 0L
                    battlePointIdx = 0
                }

                when (det.state) {
                    GameState.STAMINA_POPUP -> {
                        overlay.updateStatus("圖4 統率力不足 → 是")
                        if (det.targetX != null && det.targetY != null) {
                            acc.clickAt(det.targetX, det.targetY, config.jitterPx)
                            SystemClock.sleep(config.postClickCooldownMs)
                        } else {
                            SystemClock.sleep(60)
                        }
                    }

                    GameState.EX_POPUP -> {
                        overlay.updateStatus("圖6 EX關卡 → 是")
                        if (det.targetX != null && det.targetY != null) {
                            acc.clickAt(det.targetX, det.targetY, config.jitterPx)
                            SystemClock.sleep(config.postClickCooldownMs)
                        } else {
                            SystemClock.sleep(60)
                        }
                    }

                    GameState.VICTORY_REWARD -> {
                        overlay.updateStatus("圖3 過關獎勵 (等待)")
                        SystemClock.sleep(config.rewardWaitMs)
                        if (botActive.get() && serviceAlive.get() && det.targetX != null && det.targetY != null) {
                            overlay.updateStatus("圖3 過關獎勵 → 關閉")
                            acc.clickAt(det.targetX, det.targetY, config.jitterPx)
                            SystemClock.sleep(config.postClickCooldownMs)
                        }
                    }

                    GameState.VICTORY_MENU -> {
                        overlay.updateStatus("圖5 右上返回按鈕")
                        if (det.targetX != null && det.targetY != null) {
                            acc.clickAt(det.targetX, det.targetY, config.jitterPx)
                            SystemClock.sleep(config.postClickCooldownMs)
                        } else {
                            SystemClock.sleep(60)
                        }
                    }

                    GameState.STAGE_SELECT -> {
                        overlay.updateStatus("圖1 戰鬥開始!!")
                        if (det.targetX != null && det.targetY != null) {
                            acc.clickAt(det.targetX, det.targetY, config.jitterPx)
                            SystemClock.sleep(config.postClickCooldownMs)
                        } else {
                            SystemClock.sleep(60)
                        }
                    }

                    GameState.IN_BATTLE -> {
                        val now = SystemClock.elapsedRealtime()
                        val interval = config.battleIntervalMs
                        if (now - lastBattleClickMs >= interval) {
                            val (tx, ty) = overlay.getBattlePoint(battlePointIdx)
                            overlay.updateStatus("圖2 戰鬥點擊 #${battlePointIdx + 1}")
                            acc.clickAt(tx, ty, config.jitterPx)
                            battlePointIdx = (battlePointIdx + 1) % 2
                            lastBattleClickMs = SystemClock.elapsedRealtime()
                        }
                        val remaining = interval - (SystemClock.elapsedRealtime() - lastBattleClickMs)
                        SystemClock.sleep(remaining.coerceIn(15L, 80L))
                    }

                    GameState.UNKNOWN -> {
                        overlay.updateStatus("偵測中...")
                        SystemClock.sleep(60)
                    }
                }
            }
        }, "BattleCatsBotWorker").also { it.start() }
    }

    private fun createNotificationChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            "貓咪大戰爭自動腳本",
            NotificationManager.IMPORTANCE_LOW
        )
        mgr.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("貓咪大戰爭自動腳本執行中")
            .setContentText("懸浮控制面板已開啟，請切換至遊戲畫面")
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        serviceAlive.set(false)
        botActive.set(false)
        isServiceRunning = false
        workerThread?.interrupt()
        runCatching { workerThread?.join(800) }
        if (::overlay.isInitialized) {
            overlay.destroy()
        }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        synchronized(frameLock) {
            latestRgbaMat?.release()
            latestRgbaMat = null
            matcher.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
