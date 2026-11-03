package com.example.screenai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlin.math.min

class KapriAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: KapriAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Свайп из центра экрана: left, right, up или down (так двигаются плитки в 2048). */
    fun swipeDir(dir: String, w: Int, h: Int): Boolean {
        val cx = w / 2f
        val cy = h / 2f
        val dist = min(w, h) * 0.3f
        return when (dir.lowercase()) {
            "left" -> swipe(cx + dist, cy, cx - dist, cy)
            "right" -> swipe(cx - dist, cy, cx + dist, cy)
            "up" -> swipe(cx, cy + dist, cx, cy - dist)
            "down" -> swipe(cx, cy - dist, cx, cy + dist)
            else -> false
        }
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 180): Boolean {
        val p = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatch(p, duration)
    }

    fun tap(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        return dispatch(p, 60)
    }

    private fun dispatch(p: Path, duration: Long): Boolean {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, duration))
            .build()
        return dispatchGesture(g, null, null)
    }
}
