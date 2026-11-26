package com.battlecats.autobot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlin.random.Random

class BotAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed; we trigger gestures on demand from MediaProjection loop.
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun clickAt(x: Float, y: Float, jitterPx: Int = 4) {
        val dx = if (jitterPx > 0) Random.nextInt(-jitterPx, jitterPx + 1) else 0
        val dy = if (jitterPx > 0) Random.nextInt(-jitterPx, jitterPx + 1) else 0
        val tx = (x + dx).coerceAtLeast(1f)
        val ty = (y + dy).coerceAtLeast(1f)

        val path = Path().apply {
            moveTo(tx, ty)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 35L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    companion object {
        @Volatile
        var instance: BotAccessibilityService? = null
            private set

        fun isReady(): Boolean = instance != null
    }
}
