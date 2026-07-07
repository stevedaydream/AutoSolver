package com.stevedaydream.autodora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.sqrt

class TemplateCalibrationWindow(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: FrameLayout? = null

    companion object {
        // ★ 統一常數：與 BoardAnalyzer.analyzeBoard 完全一致
        const val TEMPLATE_SIZE = 32
        const val CROP_RATIO = 0.65f
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(screenshot: Bitmap) {
        val prefs = context.getSharedPreferences("AutoDoraPrefs", Context.MODE_PRIVATE)

        var orbSize = prefs.getInt("calibOrbSize", prefs.getInt("orbSize", 150))
        var startX = prefs.getInt("calibStartX", prefs.getInt("startX", 0))
        var startY = prefs.getInt("calibStartY", prefs.getInt("startY", 1000))

        val boardState = Array(5) { Array(6) { BoardAnalyzer.OrbType.UNKNOWN } }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        overlayView = FrameLayout(context).apply { setBackgroundColor(Color.parseColor("#CC000000")) }

        val gridView = object : View(context) {
            val paint = Paint().apply { textAlign = Paint.Align.CENTER; isAntiAlias = true }
            val gridPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f }
            val handlePaint = Paint().apply { color = Color.parseColor("#E74C3C"); style = Paint.Style.FILL; isAntiAlias = true }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                canvas.drawBitmap(screenshot, 0f, 0f, null)

                paint.textSize = orbSize * 0.4f

                for (r in 0 until 5) {
                    for (c in 0 until 6) {
                        val cx = startX + c * orbSize
                        val cy = startY + r * orbSize

                        canvas.drawRect(cx.toFloat(), cy.toFloat(), (cx + orbSize).toFloat(), (cy + orbSize).toFloat(), gridPaint)

                        val type = boardState[r][c]
                        if (type != BoardAnalyzer.OrbType.UNKNOWN) {
                            paint.color = Color.parseColor("#AA000000")
                            canvas.drawRect(cx.toFloat(), cy.toFloat(), (cx + orbSize).toFloat(), (cy + orbSize).toFloat(), paint)

                            val text = when(type) {
                                BoardAnalyzer.OrbType.FIRE -> { paint.color = Color.RED; "火" }
                                BoardAnalyzer.OrbType.WOOD -> { paint.color = Color.GREEN; "木" }
                                BoardAnalyzer.OrbType.WATER -> { paint.color = Color.BLUE; "水" }
                                BoardAnalyzer.OrbType.LIGHT -> { paint.color = Color.YELLOW; "光" }
                                BoardAnalyzer.OrbType.DARK -> { paint.color = Color.MAGENTA; "暗" }
                                BoardAnalyzer.OrbType.HEART -> { paint.color = Color.parseColor("#FF69B4"); "心" }
                                else -> ""
                            }
                            canvas.drawText(text, cx + orbSize / 2f, cy + orbSize / 1.6f, paint)
                        }
                    }
                }

                val gridRight = startX + orbSize * 6f
                val gridBottom = startY + orbSize * 5f
                canvas.drawCircle(gridRight, gridBottom, 40f, handlePaint)
                paint.color = Color.WHITE
                paint.textSize = 40f
                canvas.drawText("↘", gridRight, gridBottom + 15f, paint)
            }
        }

        var isDragging = false
        var isResizing = false
        var initialTouchX = 0f
        var initialTouchY = 0f
        var initialStartX = 0
        var initialStartY = 0
        var initialOrbSize = 0

        gridView.setOnTouchListener { _, event ->
            val touchX = event.x
            val touchY = event.y
            val gridRight = startX + orbSize * 6
            val gridBottom = startY + orbSize * 5

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = touchX
                    initialTouchY = touchY
                    initialStartX = startX
                    initialStartY = startY
                    initialOrbSize = orbSize

                    if (abs(touchX - gridRight) < 80 && abs(touchY - gridBottom) < 80) {
                        isResizing = true
                    } else if (touchX >= startX && touchX <= gridRight && touchY >= startY && touchY <= gridBottom) {
                        isDragging = true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = touchX - initialTouchX
                    val dy = touchY - initialTouchY

                    if (isResizing) {
                        var newSize = initialOrbSize + (dx / 6).toInt()
                        if (newSize < 50) newSize = 50
                        orbSize = newSize
                        gridView.invalidate()
                    } else if (isDragging) {
                        startX = initialStartX + dx.toInt()
                        startY = initialStartY + dy.toInt()
                        gridView.invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dx = touchX - initialTouchX
                    val dy = touchY - initialTouchY
                    val distanceMoved = sqrt((dx * dx + dy * dy).toDouble())

                    if (distanceMoved < 10f) {
                        val x = touchX.toInt()
                        val y = touchY.toInt()
                        if (x in startX until gridRight && y in startY until gridBottom) {
                            val c = (x - startX) / orbSize
                            val r = (y - startY) / orbSize
                            if (r in 0..4 && c in 0..5) {
                                boardState[r][c] = when (boardState[r][c]) {
                                    BoardAnalyzer.OrbType.UNKNOWN -> BoardAnalyzer.OrbType.FIRE
                                    BoardAnalyzer.OrbType.FIRE -> BoardAnalyzer.OrbType.WATER
                                    BoardAnalyzer.OrbType.WATER -> BoardAnalyzer.OrbType.WOOD
                                    BoardAnalyzer.OrbType.WOOD -> BoardAnalyzer.OrbType.LIGHT
                                    BoardAnalyzer.OrbType.LIGHT -> BoardAnalyzer.OrbType.DARK
                                    BoardAnalyzer.OrbType.DARK -> BoardAnalyzer.OrbType.HEART
                                    BoardAnalyzer.OrbType.HEART -> BoardAnalyzer.OrbType.UNKNOWN
                                }
                                gridView.invalidate()
                            }
                        }
                    }
                    isResizing = false
                    isDragging = false
                }
            }
            true
        }

        overlayView?.addView(gridView)

        val btnSave = Button(context).apply {
            text = "💾 儲存樣板\n(各標記一顆即可)"
            setBackgroundColor(Color.parseColor("#2ECC71"))
            setTextColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = 100
            }
            setOnClickListener {
                prefs.edit()
                    .putInt("calibStartX", startX)
                    .putInt("calibStartY", startY)
                    .putInt("calibOrbSize", orbSize)
                    .apply()

                var savedCount = 0
                for (r in 0 until 5) {
                    for (c in 0 until 6) {
                        val type = boardState[r][c]
                        if (type != BoardAnalyzer.OrbType.UNKNOWN) {
                            val cellX = startX + c * orbSize
                            val cellY = startY + r * orbSize

                            // ★ 關鍵修正：裁切 65% 中心區域再縮到 32x32
                            // 與 BoardAnalyzer.analyzeBoard 的裁切邏輯完全一致
                            val cropSize = (orbSize * CROP_RATIO).toInt()
                            val cropOffset = (orbSize - cropSize) / 2
                            val cropX = cellX + cropOffset
                            val cropY = cellY + cropOffset

                            if (cropX >= 0 && cropY >= 0 &&
                                cropX + cropSize <= screenshot.width &&
                                cropY + cropSize <= screenshot.height) {

                                val cropBitmap = Bitmap.createBitmap(screenshot, cropX, cropY, cropSize, cropSize)
                                val scaledBitmap = Bitmap.createScaledBitmap(cropBitmap, TEMPLATE_SIZE, TEMPLATE_SIZE, true)

                                TemplateManager.saveTemplate(context, type, scaledBitmap)

                                scaledBitmap.recycle()
                                if (cropBitmap !== scaledBitmap) cropBitmap.recycle()
                                savedCount++
                            }
                        }
                    }
                }

                if (savedCount > 0) {
                    Toast.makeText(context, "成功建立 $savedCount 個樣板！", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "未標記任何樣板。", Toast.LENGTH_SHORT).show()
                }
                hide()
            }
        }

        val btnClose = Button(context).apply {
            text = "✖ 取消"
            setBackgroundColor(Color.parseColor("#E74C3C"))
            setTextColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END; topMargin = 50; rightMargin = 50
            }
            setOnClickListener { hide() }
        }

        overlayView?.addView(btnSave)
        overlayView?.addView(btnClose)
        windowManager.addView(overlayView, params)
    }

    private fun hide() {
        overlayView?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
            overlayView = null
        }
    }
}