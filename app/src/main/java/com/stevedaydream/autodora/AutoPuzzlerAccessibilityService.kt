// --- 修改開頭：升級為 suspend 協程函式，並加入起點長按邏輯以確保遊戲判定「拾取」 ---
package com.stevedaydream.autodora

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AutoPuzzlerAccessibilityService : AccessibilityService() {

    private var floatingWindow: FloatingWindow? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("AutoPuzzler", "Accessibility Service Connected")
        floatingWindow = FloatingWindow(this, this)
        floatingWindow?.show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.d("AutoPuzzler", "Accessibility Service Interrupted")
        floatingWindow?.hide()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        floatingWindow?.hide()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        floatingWindow?.hide()
        floatingWindow = null
        super.onDestroy()
    }

    suspend fun performTap(x: Float, y: Float, holdMs: Long = 80L): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = android.graphics.Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, holdMs)
            val dispatched = dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onCancelled(d: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }, null
            )
            if (!dispatched && cont.isActive) cont.resume(false)
        }

    // 關鍵修改：改用 suspend 讓主程式能夠「等」它滑完
    suspend fun performOrbSwipe(pathPoints: List<Pair<Float, Float>>, duration: Long): Boolean = suspendCancellableCoroutine { continuation ->
        if (pathPoints.isEmpty()) {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }

        val path = Path()
        val startX = pathPoints.first().first
        val startY = pathPoints.first().second

        // 關鍵防呆：在起點微微晃動一下 (偏移 1 像素)，強迫遊戲引擎判定「按下並持有(Hold)」，防止斷珠或不拿珠
        path.moveTo(startX, startY)
        path.lineTo(startX + 1f, startY)

        // 接著再畫出真正的軌跡
        for (i in 1 until pathPoints.size) {
            path.lineTo(pathPoints[i].first, pathPoints[i].second)
        }

        val gestureBuilder = GestureDescription.Builder()
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, duration)
        gestureBuilder.addStroke(strokeDescription)

        val result = dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d("AutoPuzzler", "Swipe action completed")
                if (continuation.isActive) continuation.resume(true) // 滑動真的結束了，才回傳 true
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.d("AutoPuzzler", "Swipe action cancelled")
                if (continuation.isActive) continuation.resume(false)
            }
        }, null)

        Log.d("AutoPuzzler", "Gesture dispatch result: $result")
        if (!result && continuation.isActive) {
            continuation.resume(false)
        }
    }
}
// --- 修改結尾 ---