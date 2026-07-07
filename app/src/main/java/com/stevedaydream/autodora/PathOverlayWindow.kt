// --- 修改開頭：新增專門繪製向量軌跡的透明覆蓋層 ---
package com.stevedaydream.autodora

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.View
import android.view.WindowManager

class PathOverlayWindow(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    // --- 修改開頭：將視窗類型改為 TYPE_ACCESSIBILITY_OVERLAY ---
    fun show(pathPoints: List<Pair<Float, Float>>) {
        hide() // 顯示新路徑前，先清除舊的

        if (pathPoints.isEmpty()) return

        // 關鍵修改：使用無障礙特權圖層
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    // 視窗原點對齊螢幕 (0,0)，讓繪製座標與截圖像素座標一致
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Android 11+ 以 inset 機制決定視窗避讓，舊 flag 會被忽略；
            // 設為 0 = 不避讓狀態列/導覽列，並允許延伸進瀏海區
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        overlayView = object : View(context) {
            // 軌跡線條的畫筆 (青色、圓角)
            val linePaint = Paint().apply {
                color = Color.parseColor("#00FFFF")
                strokeWidth = 12f
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                isAntiAlias = true
            }
            // 途經點的畫筆 (黃色)
            val pointPaint = Paint().apply {
                color = Color.YELLOW
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            // 起點畫筆 (綠色)
            val startPaint = Paint().apply {
                color = Color.GREEN
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            // 終點畫筆 (紅色)
            val endPaint = Paint().apply {
                color = Color.RED
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (pathPoints.size < 2) return
                val n = pathPoints.size

                // ── 1. 統計每條格子邊被走過幾次，並分配橫向錯開量 ──
                // 用無向邊作 key（座標較小的端點排前面）
                data class SegKey(val ax: Int, val ay: Int, val bx: Int, val by: Int)
                fun segKey(p1: Pair<Float, Float>, p2: Pair<Float, Float>): SegKey {
                    val (x1, y1) = p1; val (x2, y2) = p2
                    return if (x1 < x2 || (x1 == x2 && y1 < y2))
                        SegKey(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())
                    else SegKey(x2.toInt(), y2.toInt(), x1.toInt(), y1.toInt())
                }

                // 第一遍：統計每條邊總共被走幾次
                val totalCount = HashMap<SegKey, Int>()
                for (i in 0 until n - 1) {
                    val k = segKey(pathPoints[i], pathPoints[i + 1])
                    totalCount[k] = (totalCount[k] ?: 0) + 1
                }

                // 第二遍：計算每段的橫向偏移
                val usedCount = HashMap<SegKey, Int>()
                val offsets   = FloatArray(n - 1)
                val LATERAL   = 14f   // 重疊路段之間的橫向錯開距離（px）

                for (i in 0 until n - 1) {
                    val k     = segKey(pathPoints[i], pathPoints[i + 1])
                    val total = totalCount[k] ?: 1
                    val idx   = usedCount[k] ?: 0
                    usedCount[k] = idx + 1
                    // 多條線均分在中心兩側：-half … 0 … +half
                    offsets[i] = if (total > 1) (idx - (total - 1) / 2.0f) * LATERAL else 0f
                }

                // ── 2. 逐段繪製：綠→黃→紅漸層 + 垂直偏移 ─────────
                val segPaint = Paint().apply {
                    strokeWidth = 12f
                    style       = Paint.Style.STROKE
                    strokeCap   = Paint.Cap.ROUND
                    isAntiAlias = true
                }

                for (i in 0 until n - 1) {
                    val p1     = pathPoints[i]
                    val p2     = pathPoints[i + 1]
                    val offset = offsets[i]

                    // 顏色：依步驟比例從綠漸變到紅
                    val frac = if (n > 2) i.toFloat() / (n - 2) else 0f
                    val r = (frac * 2f * 255f).toInt().coerceIn(0, 255)
                    val g = ((1f - frac) * 2f * 255f).toInt().coerceIn(0, 255)
                    segPaint.color = Color.argb(220, r, g, 30)

                    // 計算路段垂直方向的單位向量，乘以偏移量
                    val dx  = p2.first  - p1.first
                    val dy  = p2.second - p1.second
                    val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat().coerceAtLeast(0.1f)
                    val nx  = -dy / len * offset   // 垂直 x
                    val ny  =  dx / len * offset   // 垂直 y

                    canvas.drawLine(
                        p1.first + nx, p1.second + ny,
                        p2.first + nx, p2.second + ny,
                        segPaint
                    )
                }

                // ── 3. 起點（綠）、終點（紅）、途經點（黃）─────────
                for ((index, point) in pathPoints.withIndex()) {
                    val paint = when (index) {
                        0     -> startPaint
                        n - 1 -> endPaint
                        else  -> pointPaint
                    }
                    val radius = if (index == 0 || index == n - 1) 18f else 8f
                    canvas.drawCircle(point.first, point.second, radius, paint)
                }
            }
        }

        windowManager.addView(overlayView, params)
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
// --- 修改結尾 ---