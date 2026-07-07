package com.stevedaydream.autodora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast

class BoardPreviewWindow(
    private val context: Context,
    private val onCollectSamples: (() -> Unit)? = null
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: FrameLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private val prefs = context.getSharedPreferences("AutoDoraPrefs", Context.MODE_PRIVATE)

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (overlayView != null) return

        val displayMetrics = context.resources.displayMetrics
        var orbSize = prefs.getInt("orbSize", displayMetrics.widthPixels / 6)
        var startX = prefs.getInt("startX", 0)
        var startY = prefs.getInt("startY", displayMetrics.heightPixels / 2)

        // 關鍵修改：使用無障礙特權圖層
        params = WindowManager.LayoutParams(
            orbSize * 6,
            orbSize * 5,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }

        overlayView = FrameLayout(context)

        // 1. 自訂 View：畫出 6x5 網格
        val gridView = object : View(context) {
            val linePaint = Paint().apply {
                color = Color.RED
                strokeWidth = 5f
                style = Paint.Style.STROKE
            }
            val bgPaint = Paint().apply {
                color = Color.parseColor("#44FF0000") // 半透明紅色背景，方便看清楚範圍
                style = Paint.Style.FILL
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat()
                val h = height.toFloat()
                canvas.drawRect(0f, 0f, w, h, bgPaint)
                canvas.drawRect(0f, 0f, w, h, linePaint)

                val colWidth = w / 6f
                val rowHeight = h / 5f

                for (i in 1..5) canvas.drawLine(i * colWidth, 0f, i * colWidth, h, linePaint)
                for (i in 1..4) canvas.drawLine(0f, i * rowHeight, w, i * rowHeight, linePaint)
            }
        }

        // 2. 儲存與關閉按鈕 (放在左上角)
        val btnSave = Button(context).apply {
            text = "✅ 儲存範圍"
            setBackgroundColor(Color.parseColor("#2ECC71"))
            setTextColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.START }

            setOnClickListener {
                // 視窗參數座標與螢幕像素座標存在系統內縮偏移（狀態列/瀏海），
                // 必須存 View 的真實螢幕位置，才能對上截圖的像素座標
                val loc = IntArray(2)
                overlayView?.getLocationOnScreen(loc)
                prefs.edit()
                    .putInt("startX", loc[0])
                    .putInt("startY", loc[1])
                    .putInt("orbSize", orbSize)
                    .apply()
                Toast.makeText(context, "辨識範圍已儲存！", Toast.LENGTH_SHORT).show()
                hide()
            }
        }

        // 3. 採集樣本按鈕 (放在左下角，僅在有 callback 時顯示) — 宣告在此，addView 在最後
        val btnCollect: Button? = if (onCollectSamples != null) Button(context).apply {
            text = "📝 採集樣本"
            setBackgroundColor(Color.parseColor("#1ABC9C"))
            setTextColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.START }
            setOnClickListener { onCollectSamples.invoke() }
        } else null

        // 4. 縮放控制把手 (放在右下角)
        val resizeHandle = Button(context).apply {
            text = "↘"
            textSize = 24f
            setBackgroundColor(Color.parseColor("#E74C3C"))
            setTextColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(120, 120).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            }
        }

        // --- 拖曳整個視窗的邏輯 (綁定在網格上) ---
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        gridView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }

        // --- 拖曳右下角縮放網格的邏輯 ---
        var resizeInitialTouchX = 0f
        resizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resizeInitialTouchX = event.rawX
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - resizeInitialTouchX
                    // 每次移動計算新的珠子大小 (限制最小 50px，最大 300px 防止崩潰)
                    val newOrbSize = (orbSize + dx / 6).toInt().coerceIn(50, 300)
                    orbSize = newOrbSize
                    params.width = orbSize * 6
                    params.height = orbSize * 5
                    windowManager.updateViewLayout(overlayView, params)
                    resizeInitialTouchX = event.rawX
                    true
                }
                else -> false
            }
        }

        // 加入順序決定 FrameLayout z 層：後加 = 較上層 = 優先接收觸控
        overlayView?.addView(gridView)      // 最底層（拖曳整個視窗）
        overlayView?.addView(btnSave)       // 左上角按鈕
        if (btnCollect != null) overlayView?.addView(btnCollect) // 左下角按鈕（在 gridView 之後 = 優先攔截觸控）
        overlayView?.addView(resizeHandle)  // 右下角縮放把手

        windowManager.addView(overlayView, params)

        // 儲存的 startX/startY 是螢幕像素座標，但 params.x/y 是視窗座標（有系統內縮偏移）；
        // 附加後量測實際位置，把偏移補回去，讓網格顯示在儲存的位置上
        overlayView?.post {
            val v = overlayView ?: return@post
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val dx = loc[0] - startX
            val dy = loc[1] - startY
            if (dx != 0 || dy != 0) {
                params.x -= dx
                params.y -= dy
                if (v.isAttachedToWindow) windowManager.updateViewLayout(v, params)
            }
        }
    }

    fun hide() {
        overlayView?.let {
            if (it.isAttachedToWindow) {
                windowManager.removeView(it)
            }
            overlayView = null
        }
    }
}