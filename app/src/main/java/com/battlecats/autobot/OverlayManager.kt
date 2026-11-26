package com.battlecats.autobot

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import kotlin.math.roundToInt

class OverlayManager(
    private val context: Context,
    private val config: BotConfig,
    private val onToggleRun: (Boolean) -> Unit,
    private val onCloseRequested: () -> Unit,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var tvStatus: TextView? = null
    private var btnToggleRun: Button? = null
    private var btnToggleMarkers: Button? = null

    private var marker1: TextView? = null
    private var marker1Params: WindowManager.LayoutParams? = null
    private var marker2: TextView? = null
    private var marker2Params: WindowManager.LayoutParams? = null

    private var isRunning = false
    private var markersVisible = true

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private fun createOverlayParams(
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        touchable: Boolean = true,
    ): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (!touchable) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)

        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragListener(
        view: View,
        params: WindowManager.LayoutParams,
        onMoved: (() -> Unit)? = null,
    ) {
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val (sw, sh) = screenSize()
                    params.x = (initX + (event.rawX - touchX)).roundToInt()
                        .coerceIn(0, (sw - view.width).coerceAtLeast(0))
                    params.y = (initY + (event.rawY - touchY)).roundToInt()
                        .coerceIn(0, (sh - view.height).coerceAtLeast(0))
                    wm.updateViewLayout(view, params)
                    onMoved?.invoke()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    onMoved?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    private fun buildMarker(label: String, bgRes: Int): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundResource(bgRes)
        }
    }

    fun show() {
        if (panelView != null) return
        val (sw, sh) = screenSize()
        val markerSize = dp(40)

        // Marker #1 (Blue)
        val m1 = buildMarker("1", R.drawable.marker_one)
        val m1x = (config.point1XRatio * sw - markerSize / 2f).roundToInt()
        val m1y = (config.point1YRatio * sh - markerSize / 2f).roundToInt()
        val m1Params = createOverlayParams(markerSize, markerSize, m1x, m1y, touchable = true)
        attachDragListener(m1, m1Params) {
            val (curW, curH) = screenSize()
            val cx = m1Params.x + markerSize / 2f
            val cy = m1Params.y + markerSize / 2f
            config.point1XRatio = cx / curW.toFloat()
            config.point1YRatio = cy / curH.toFloat()
        }
        wm.addView(m1, m1Params)
        marker1 = m1
        marker1Params = m1Params

        // Marker #2 (Orange)
        val m2 = buildMarker("2", R.drawable.marker_two)
        val m2x = (config.point2XRatio * sw - markerSize / 2f).roundToInt()
        val m2y = (config.point2YRatio * sh - markerSize / 2f).roundToInt()
        val m2Params = createOverlayParams(markerSize, markerSize, m2x, m2y, touchable = true)
        attachDragListener(m2, m2Params) {
            val (curW, curH) = screenSize()
            val cx = m2Params.x + markerSize / 2f
            val cy = m2Params.y + markerSize / 2f
            config.point2XRatio = cx / curW.toFloat()
            config.point2YRatio = cy / curH.toFloat()
        }
        wm.addView(m2, m2Params)
        marker2 = m2
        marker2Params = m2Params

        // Floating control panel
        val panel = LayoutInflater.from(context).inflate(R.layout.overlay_panel, null)
        val pParams = createOverlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            dp(20),
            dp(80),
            touchable = true,
        )
        val dragHandle = panel.findViewById<TextView>(R.id.tvDragHandle)
        attachDragListener(dragHandle, pParams)
        // Allow dragging from the whole header, while keeping params attached to panel root:
        dragHandle.setOnTouchListener(null)
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initX = pParams.x
                    initY = pParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val (curW, curH) = screenSize()
                    pParams.x = (initX + (event.rawX - touchX)).roundToInt()
                        .coerceIn(0, (curW - panel.width).coerceAtLeast(0))
                    pParams.y = (initY + (event.rawY - touchY)).roundToInt()
                        .coerceIn(0, (curH - panel.height).coerceAtLeast(0))
                    wm.updateViewLayout(panel, pParams)
                    true
                }
                else -> false
            }
        }

        tvStatus = dragHandle
        btnToggleRun = panel.findViewById(R.id.btnToggleRun)
        btnToggleMarkers = panel.findViewById(R.id.btnToggleMarkers)
        val btnClose = panel.findViewById<Button>(R.id.btnClose)

        btnToggleRun?.setOnClickListener {
            setRunningState(!isRunning)
            onToggleRun(isRunning)
        }

        btnToggleMarkers?.setOnClickListener {
            markersVisible = !markersVisible
            val vis = if (markersVisible) View.VISIBLE else View.GONE
            marker1?.visibility = vis
            marker2?.visibility = vis
            btnToggleMarkers?.text = if (markersVisible) "隱藏" else "準星"
        }

        btnClose.setOnClickListener {
            onCloseRequested()
        }

        wm.addView(panel, pParams)
        panelView = panel
        panelParams = pParams
    }

    fun setRunningState(running: Boolean) {
        isRunning = running
        mainHandler.post {
            btnToggleRun?.text = if (running) "暫停" else "開始"
            btnToggleRun?.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (running) Color.parseColor("#FB8C00") else Color.parseColor("#4CAF50")
            )
            if (!running) {
                tvStatus?.text = "≡ 貓戰腳本 (已暫停)"
            }
            // When running, make markers pass-through so AccessibilityService clicks hit the game underneath!
            listOf(marker1 to marker1Params, marker2 to marker2Params).forEach { (view, lp) ->
                if (view != null && lp != null) {
                    lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        (if (running) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
                    view.alpha = if (running) 0.45f else 1.0f
                    wm.updateViewLayout(view, lp)
                }
            }
        }
    }

    fun updateStatus(text: String) {
        mainHandler.post {
            tvStatus?.text = "≡ $text"
        }
    }

    fun syncMarkersToScreenSize() {
        mainHandler.post {
            val (sw, sh) = screenSize()
            val markerSize = dp(40)
            marker1?.let { m1 ->
                marker1Params?.let { p1 ->
                    p1.x = (config.point1XRatio * sw - markerSize / 2f).roundToInt()
                        .coerceIn(0, (sw - markerSize).coerceAtLeast(0))
                    p1.y = (config.point1YRatio * sh - markerSize / 2f).roundToInt()
                        .coerceIn(0, (sh - markerSize).coerceAtLeast(0))
                    wm.updateViewLayout(m1, p1)
                }
            }
            marker2?.let { m2 ->
                marker2Params?.let { p2 ->
                    p2.x = (config.point2XRatio * sw - markerSize / 2f).roundToInt()
                        .coerceIn(0, (sw - markerSize).coerceAtLeast(0))
                    p2.y = (config.point2YRatio * sh - markerSize / 2f).roundToInt()
                        .coerceIn(0, (sh - markerSize).coerceAtLeast(0))
                    wm.updateViewLayout(m2, p2)
                }
            }
        }
    }

    fun getBattlePoint(index: Int): Pair<Float, Float> {
        val view = if (index == 0) marker1 else marker2
        if (view != null && view.visibility == View.VISIBLE && view.isAttachedToWindow) {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            if (loc[0] > 0 || loc[1] > 0) {
                return (loc[0] + view.width / 2f) to (loc[1] + view.height / 2f)
            }
        }
        val (sw, sh) = screenSize()
        return if (index == 0) {
            (config.point1XRatio * sw) to (config.point1YRatio * sh)
        } else {
            (config.point2XRatio * sw) to (config.point2YRatio * sh)
        }
    }

    fun destroy() {
        mainHandler.post {
            listOf(panelView, marker1, marker2).forEach { v ->
                if (v != null && v.isAttachedToWindow) {
                    runCatching { wm.removeViewImmediate(v) }
                }
            }
            panelView = null
            marker1 = null
            marker2 = null
        }
    }
}
