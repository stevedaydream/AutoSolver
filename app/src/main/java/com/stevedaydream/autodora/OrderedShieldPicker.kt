package com.stevedaydream.autodora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.stevedaydream.autodora.BoardAnalyzer.OrbType
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 起結有序盾：直接在遊戲盤面上點選「起點珠」與「終點珠」。
 * 每點一格就在該珠周圍彈出 6 屬性圓環指定它的屬性——起終點珠帶特效動畫，常讓辨識失準，
 * 由使用者指定可避免誤判。全螢幕覆蓋層只在選擇期間攔截觸控，完成或取消即移除。
 */
class OrderedShieldPicker(private val context: Context) {
    /** [fixes]：起終點以外、辨識不到而由使用者補選的格子。 */
    data class Selection(val startCell: Int, val startType: OrbType, val endCell: Int, val endType: OrbType,
                         val fixes: Map<Int, OrbType> = emptyMap()) {
        val overrides: Map<Int, OrbType> get() = fixes + mapOf(startCell to startType, endCell to endType)
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: PickerView? = null

    val isShowing: Boolean get() = view?.isAttachedToWindow == true

    fun show(board: Array<Array<OrbType>>, geometry: BoardGeometry, onDone: (Selection) -> Unit, onCancel: () -> Unit) {
        hide()
        val v = PickerView(context, board, geometry, { sel -> hide(); onDone(sel) }, { hide(); onCancel() })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 與截圖、手勢同一座標系：原點對齊螢幕 (0,0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        windowManager.addView(v, params)
        view = v
    }

    fun hide() {
        view?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        view = null
    }

    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private class PickerView(
        context: Context,
        private val board: Array<Array<OrbType>>,
        private val g: BoardGeometry,
        private val onDone: (Selection) -> Unit,
        private val onCancel: () -> Unit
    ) : View(context) {
        private val types = listOf(OrbType.WATER, OrbType.FIRE, OrbType.WOOD, OrbType.LIGHT, OrbType.DARK, OrbType.HEART)
        private val typeColors = mapOf(
            OrbType.WATER to Color.parseColor("#3B82F6"), OrbType.FIRE to Color.parseColor("#EF4444"),
            OrbType.WOOD to Color.parseColor("#22C55E"), OrbType.LIGHT to Color.parseColor("#EAB308"),
            OrbType.DARK to Color.parseColor("#A855F7"), OrbType.HEART to Color.parseColor("#EC4899")
        )
        private val dp = resources.displayMetrics.density

        private var startCell = -1
        private var startType: OrbType? = null
        private var endCell = -1
        private var endType: OrbType? = null
        private val fixes = LinkedHashMap<Int, OrbType>()

        /** 起終點選好後仍辨識不到的格子（第 ③ 步要補選）。 */
        private fun remainingUnknown(): List<Int> = (0 until g.rows * g.cols).filter { cell ->
            board[cell / g.cols][cell % g.cols] == OrbType.UNKNOWN && cell != startCell && cell != endCell && cell !in fixes
        }
        private var ringCell = -1
        private val ringX = FloatArray(6)
        private val ringY = FloatArray(6)
        private val ringR get() = g.orbSize * 0.34f
        private val cancelRect = RectF()
        private val bannerRect = RectF()

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; color = Color.WHITE
        }

        private fun cellLeft(cell: Int) = g.startX + (cell % g.cols) * g.orbSize
        private fun cellTop(cell: Int) = g.startY + (cell / g.cols) * g.orbSize
        private fun cellCx(cell: Int) = cellLeft(cell) + g.orbSize / 2f
        private fun cellCy(cell: Int) = cellTop(cell) + g.orbSize / 2f

        /** 圓環 6 顆按鈕繞著格子中心；靠邊時整個圓環往內推，避免超出螢幕。 */
        private fun layoutRing(cell: Int) {
            val radius = g.orbSize * 0.95f
            val margin = radius + ringR + 4 * dp
            val cx = cellCx(cell).coerceIn(margin, width - margin)
            val cy = cellCy(cell).coerceIn(margin, height - margin)
            for (i in 0 until 6) {
                val angle = Math.toRadians(-90.0 + i * 60.0)
                ringX[i] = cx + (radius * cos(angle)).toFloat()
                ringY[i] = cy + (radius * sin(angle)).toFloat()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 盤面外輕微變暗，提示目前在選擇模式
            canvas.drawColor(Color.argb(70, 0, 0, 0))
            text.textSize = g.orbSize * 0.2f
            for (cell in 0 until g.rows * g.cols) {
                val l = cellLeft(cell); val t = cellTop(cell)
                val type = board[cell / g.cols][cell % g.cols]
                stroke.strokeWidth = 2 * dp
                stroke.color = Color.argb(140, 255, 255, 255)
                canvas.drawRect(l + 2, t + 2, l + g.orbSize - 2, t + g.orbSize - 2, stroke)
                if (type == OrbType.UNKNOWN && cell != startCell && cell != endCell && cell !in fixes) {
                    fill.color = Color.argb(110, 239, 68, 68)
                    canvas.drawRect(l + 2, t + 2, l + g.orbSize - 2, t + g.orbSize - 2, fill)
                    canvas.drawText("?", l + g.orbSize / 2f, t + g.orbSize * 0.6f, text)
                }
            }
            if (startCell >= 0) drawMark(canvas, startCell, "起", Color.parseColor("#22C55E"), startType)
            if (endCell >= 0) drawMark(canvas, endCell, "終", Color.parseColor("#EF4444"), endType)
            for ((cell, type) in fixes) drawMark(canvas, cell, "補", Color.parseColor("#64748B"), type)

            // 上方說明列
            val title = when {
                startCell < 0 -> "① 點選起點珠，再選屬性"
                endCell < 0 -> "② 點選終點珠，再選屬性"
                else -> "③ 補選辨識不到的 ? 格（剩 ${remainingUnknown().size} 格）"
            }
            text.textSize = 16 * resources.displayMetrics.scaledDensity
            val bw = text.measureText(title) + 72 * dp
            val bTop = (g.startY - 64 * dp).coerceAtLeast(40 * dp)
            bannerRect.set(width / 2f - bw / 2f, bTop, width / 2f + bw / 2f, bTop + 48 * dp)
            fill.color = Color.parseColor("#F0121A23")
            canvas.drawRoundRect(bannerRect, 24 * dp, 24 * dp, fill)
            canvas.drawText(title, bannerRect.centerX() - 18 * dp, bannerRect.centerY() + text.textSize * 0.35f, text)
            cancelRect.set(bannerRect.right - 46 * dp, bannerRect.top + 6 * dp, bannerRect.right - 10 * dp, bannerRect.bottom - 6 * dp)
            canvas.drawText("✕", cancelRect.centerX(), cancelRect.centerY() + text.textSize * 0.35f, text)

            if (ringCell >= 0) {
                stroke.color = Color.WHITE; stroke.strokeWidth = 3 * dp
                canvas.drawRect(cellLeft(ringCell), cellTop(ringCell), cellLeft(ringCell) + g.orbSize, cellTop(ringCell) + g.orbSize, stroke)
                val recognized = board[ringCell / g.cols][ringCell % g.cols]
                text.textSize = ringR * 0.95f
                for (i in 0 until 6) {
                    val type = types[i]
                    fill.color = typeColors.getValue(type)
                    canvas.drawCircle(ringX[i], ringY[i], ringR, fill)
                    stroke.color = if (type == recognized) Color.WHITE else Color.argb(200, 0, 0, 0)
                    stroke.strokeWidth = if (type == recognized) 4 * dp else 2 * dp
                    canvas.drawCircle(ringX[i], ringY[i], ringR, stroke)
                    canvas.drawText(ClearGoal.label(type), ringX[i], ringY[i] + text.textSize * 0.36f, text)
                }
            }
        }

        private fun drawMark(canvas: Canvas, cell: Int, label: String, color: Int, type: OrbType?) {
            stroke.color = color; stroke.strokeWidth = 5 * dp
            canvas.drawCircle(cellCx(cell), cellCy(cell), g.orbSize * 0.46f, stroke)
            fill.color = color
            val r = g.orbSize * 0.17f
            canvas.drawCircle(cellLeft(cell) + r + 4 * dp, cellTop(cell) + r + 4 * dp, r, fill)
            text.textSize = r * 1.1f
            val badge = if (type != null) "$label${ClearGoal.label(type)}" else label
            canvas.drawText(badge.take(1), cellLeft(cell) + r + 4 * dp, cellTop(cell) + r + 4 * dp + text.textSize * 0.36f, text)
            if (type != null) {
                fill.color = typeColors.getValue(type)
                canvas.drawCircle(cellLeft(cell) + g.orbSize - r - 4 * dp, cellTop(cell) + r + 4 * dp, r, fill)
                canvas.drawText(ClearGoal.label(type), cellLeft(cell) + g.orbSize - r - 4 * dp, cellTop(cell) + r + 4 * dp + text.textSize * 0.36f, text)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_UP) return true
            val x = event.x; val y = event.y
            if (cancelRect.contains(x, y)) { onCancel(); return true }
            if (ringCell >= 0) {
                for (i in 0 until 6) if (hypot(x - ringX[i], y - ringY[i]) <= ringR * 1.15f) {
                    choose(ringCell, types[i]); return true
                }
                ringCell = -1; invalidate(); return true   // 點圓環外：收起圓環
            }
            val cell = g.cellAt(x, y)
            if (cell < 0) return true
            if (cell == startCell || cell == endCell) return true   // 已選過的起終點不再改
            if (endCell >= 0 && cell !in remainingUnknown()) return true  // 第 ③ 步只能點 ? 格
            ringCell = cell
            layoutRing(cell)
            invalidate()
            return true
        }

        private fun choose(cell: Int, type: OrbType) {
            ringCell = -1
            when {
                startCell < 0 -> { startCell = cell; startType = type }
                endCell < 0 -> { endCell = cell; endType = type }
                else -> fixes[cell] = type
            }
            if (endCell >= 0 && remainingUnknown().isEmpty()) {
                onDone(Selection(startCell, startType!!, endCell, endType!!, LinkedHashMap(fixes)))
            } else invalidate()
        }
    }
}
