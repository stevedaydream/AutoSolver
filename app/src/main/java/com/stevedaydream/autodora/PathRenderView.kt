package com.stevedaydream.autodora

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

enum class PathPreviewTheme(val label: String) {
    LEGACY_INSPIRED("AutoDora 風格"),
    CURRENT_GRADIENT("原版漸層"),
    HIGH_CONTRAST("高對比"),
    /** 依舊版 drawPath／drawAL／getRainbowColor 位元組碼參數還原（新增於最後，保留既有偏好序號）。 */
    LEGACY_CLASSIC("舊版還原")
}

enum class PathColorMode(val label: String) { RAINBOW("彩虹"), SOLID("純色") }

data class PathRenderStyle(
    val theme: PathPreviewTheme = PathPreviewTheme.LEGACY_INSPIRED,
    val colorMode: PathColorMode = PathColorMode.RAINBOW,
    val showStepNumbers: Boolean = false,
    val showShapeMask: Boolean = true,
    val solidColor: Int = Color.parseColor("#22D3EE")
)

/**
 * 路徑畫布（Legacy-Inspired：多層輪廓 + 方向箭頭 + 起終點）。
 * setModel 時預先建立所有 Path／顏色／矩形；onDraw 只做繪製，不配置物件。
 * 此 View 只畫圖，不參與觸控；所在視窗須維持 FLAG_NOT_TOUCHABLE。
 */
class PathRenderView(context: Context) : View(context) {
    private var model: PathRenderModel? = null
    private var style = PathRenderStyle()
    private var progress = 1f

    private var segmentColors = IntArray(0)
    private var classicOuter = IntArray(0)
    private var classicInner = IntArray(0)
    private var classicArrow: Path? = null
    private var classicScale = 1f
    private var arrowPaths: List<Path> = emptyList()
    private var shapeRects: List<RectF> = emptyList()
    private var shapeColors = IntArray(0)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelRect = RectF()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; color = Color.WHITE
    }

    fun setModel(model: PathRenderModel?, style: PathRenderStyle) {
        this.model = model
        this.style = style
        if (model != null) {
            val n = model.segments.size
            segmentColors = IntArray(n) { segmentColor(it, n, model.segments[it].fraction) }
            val colored = style.colorMode == PathColorMode.RAINBOW
            classicOuter = IntArray(n) { LegacyPathStyle.outerColor(it, n, colored) }
            classicInner = IntArray(n) { LegacyPathStyle.innerColor(it, n, colored) }
            classicScale = LegacyPathStyle.scaleFor(model.geometry.orbSize)
            classicArrow = model.segments.lastOrNull()?.let { last ->
                // 舊版 drawAL：箭頭尖端在終點，沿最後一步方向往回 40px、半寬 18px
                val dx = last.x2 - last.x1; val dy = last.y2 - last.y1
                val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.1f)
                val ux = dx / len; val uy = dy / len
                val h = LegacyPathStyle.ARROW_LENGTH_PX * classicScale
                val w = LegacyPathStyle.ARROW_HALF_WIDTH_PX * classicScale
                val bx = last.x2 - ux * h; val by = last.y2 - uy * h
                Path().apply {
                    moveTo(last.x2, last.y2); lineTo(bx - uy * w, by + ux * w); lineTo(bx + uy * w, by - ux * w); close()
                }
            }
            arrowPaths = model.arrows.map { a ->
                Path().apply { moveTo(a.tipX, a.tipY); lineTo(a.leftX, a.leftY); lineTo(a.rightX, a.rightY); close() }
            }
            val g = model.geometry
            val inset = g.orbSize * 0.06f
            shapeRects = model.shapeCells.map { (cell, _) ->
                val left = g.startX + (cell % g.cols) * g.orbSize
                val top = g.startY + (cell / g.cols) * g.orbSize
                RectF(left + inset, top + inset, left + g.orbSize - inset, top + g.orbSize - inset)
            }
            shapeColors = IntArray(model.shapeCells.size) { orbTint(model.shapeCells[it].second) }
            textPaint.textSize = g.orbSize * 0.2f
        } else {
            segmentColors = IntArray(0); arrowPaths = emptyList(); shapeRects = emptyList(); shapeColors = IntArray(0)
        }
        invalidate()
    }

    fun setProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        invalidate()
    }

    private fun segmentColor(index: Int, count: Int, fraction: Float): Int = when (style.theme) {
        PathPreviewTheme.LEGACY_CLASSIC -> LegacyPathStyle.innerColor(index, count, style.colorMode == PathColorMode.RAINBOW)
        PathPreviewTheme.CURRENT_GRADIENT -> {
            val r = (fraction * 2f * 255f).toInt().coerceIn(0, 255)
            val g = ((1f - fraction) * 2f * 255f).toInt().coerceIn(0, 255)
            Color.argb(220, r, g, 30)
        }
        PathPreviewTheme.HIGH_CONTRAST -> Color.parseColor("#FFEB3B")
        PathPreviewTheme.LEGACY_INSPIRED -> if (style.colorMode == PathColorMode.SOLID) style.solidColor else {
            // 彩虹：沿用舊版 getRainbowColor 的 7 個錨點與內插
            LegacyPathStyle.rainbowColor(index, count)
        }
    }

    private fun orbTint(type: BoardAnalyzer.OrbType?): Int = when (type) {
        BoardAnalyzer.OrbType.WATER -> Color.argb(70, 59, 130, 246)
        BoardAnalyzer.OrbType.FIRE -> Color.argb(70, 239, 68, 68)
        BoardAnalyzer.OrbType.WOOD -> Color.argb(70, 34, 197, 94)
        BoardAnalyzer.OrbType.LIGHT -> Color.argb(70, 250, 204, 21)
        BoardAnalyzer.OrbType.DARK -> Color.argb(70, 168, 85, 247)
        BoardAnalyzer.OrbType.HEART -> Color.argb(70, 244, 114, 182)
        else -> Color.argb(60, 255, 255, 255)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = model ?: return
        if (m.segments.isEmpty()) return
        val w = m.lineWidth
        val visible = m.visibleSegments(progress)

        if (style.showShapeMask) {
            for (i in shapeRects.indices) {
                fillPaint.color = shapeColors[i]
                canvas.drawRoundRect(shapeRects[i], w, w, fillPaint)
            }
        }

        when (style.theme) {
            PathPreviewTheme.LEGACY_INSPIRED -> {
                drawSegments(canvas, m, visible, w * 2.1f, Color.argb(170, 0, 0, 0))
                drawSegments(canvas, m, visible, w * 1.45f, Color.argb(230, 255, 255, 255))
                drawSegments(canvas, m, visible, w, null)
            }
            PathPreviewTheme.CURRENT_GRADIENT -> drawSegments(canvas, m, visible, w, null)
            PathPreviewTheme.HIGH_CONTRAST -> {
                drawSegments(canvas, m, visible, w * 2.2f, Color.BLACK)
                drawSegments(canvas, m, visible, w * 1.1f, null)
            }
            PathPreviewTheme.LEGACY_CLASSIC -> {
                val k = classicScale
                drawSegments(canvas, m, visible, LegacyPathStyle.OUTER_OUTLINE_PX * k, Color.BLACK)
                drawSegments(canvas, m, visible, LegacyPathStyle.OUTER_COLOR_PX * k, null, classicOuter)
                drawSegments(canvas, m, visible, LegacyPathStyle.INNER_OUTLINE_PX * k, Color.BLACK)
                drawSegments(canvas, m, visible, LegacyPathStyle.INNER_COLOR_PX * k, null, classicInner)
            }
        }
        drawPartialSegment(canvas, m, visible)

        if (style.theme == PathPreviewTheme.LEGACY_CLASSIC) {
            val arrow = classicArrow
            if (arrow != null && progress >= 1f) {
                fillPaint.color = LegacyPathStyle.ARROW_FILL
                canvas.drawPath(arrow, fillPaint)
                strokePaint.color = LegacyPathStyle.ARROW_FILL
                strokePaint.strokeWidth = LegacyPathStyle.ARROW_STROKE_PX * classicScale
                canvas.drawPath(arrow, strokePaint)
                strokePaint.color = Color.BLACK
                strokePaint.strokeWidth = LegacyPathStyle.ARROW_OUTLINE_PX * classicScale
                canvas.drawPath(arrow, strokePaint)
            }
        } else if (style.theme != PathPreviewTheme.CURRENT_GRADIENT) {
            fillPaint.color = if (style.theme == PathPreviewTheme.HIGH_CONTRAST) Color.WHITE else Color.argb(240, 255, 255, 255)
            strokePaint.color = Color.BLACK
            strokePaint.strokeWidth = w * 0.35f
            for (i in m.arrows.indices) {
                if (m.arrows[i].segmentIndex >= visible) continue
                canvas.drawPath(arrowPaths[i], fillPaint)
                canvas.drawPath(arrowPaths[i], strokePaint)
            }
        }

        drawMarkers(canvas, m)
        if (progress < 1f) {
            val (fx, fy) = m.pointAt(progress)
            fillPaint.color = Color.argb(230, 255, 255, 255)
            canvas.drawCircle(fx, fy, m.markerRadius * 0.8f, fillPaint)
            strokePaint.color = Color.BLACK; strokePaint.strokeWidth = w * 0.4f
            canvas.drawCircle(fx, fy, m.markerRadius * 0.8f, strokePaint)
        }
        // 步序最後畫，壓在線、箭頭與起終點標記之上；預演時隨手指抵達逐步出現
        if (style.showStepNumbers) drawStepLabels(canvas, m, if (progress >= 1f) m.segments.size else visible)
    }

    private fun drawStepLabels(canvas: Canvas, m: PathRenderModel, completedSteps: Int) {
        val ts = textPaint.textSize
        val padX = ts * 0.35f
        for (label in m.stepLabels) {
            val text = label.textAt(completedSteps) ?: continue
            val half = textPaint.measureText(text) / 2f + padX
            val top = label.y - ts * 1.05f
            val bottom = label.y + ts * 0.3f
            labelRect.set(label.x - half, top, label.x + half, bottom)
            fillPaint.color = Color.argb(215, 0, 0, 0)
            canvas.drawRoundRect(labelRect, ts * 0.6f, ts * 0.6f, fillPaint)
            canvas.drawText(text, label.x, label.y, textPaint)
        }
    }

    private fun drawSegments(canvas: Canvas, m: PathRenderModel, count: Int, width: Float, fixedColor: Int?,
                             colors: IntArray = segmentColors) {
        linePaint.strokeWidth = width
        for (i in 0 until count) {
            val s = m.segments[i]
            linePaint.color = fixedColor ?: colors[i]
            canvas.drawLine(s.x1, s.y1, s.x2, s.y2, linePaint)
        }
    }

    /** 預演時正在走的那一段只畫到手指位置。 */
    private fun drawPartialSegment(canvas: Canvas, m: PathRenderModel, visible: Int) {
        if (progress >= 1f || visible >= m.segments.size) return
        val s = m.segments[visible]
        val (fx, fy) = m.pointAt(progress)
        linePaint.strokeWidth = m.lineWidth
        linePaint.color = segmentColors[visible]
        canvas.drawLine(s.x1, s.y1, fx, fy, linePaint)
    }

    private fun drawMarkers(canvas: Canvas, m: PathRenderModel) {
        val r = m.markerRadius
        if (style.theme == PathPreviewTheme.LEGACY_CLASSIC) {
            // 舊版起點：半徑 20px 的圓圈，套用同樣的黑／白（或灰）雙層描邊
            val k = classicScale
            val radius = LegacyPathStyle.START_RADIUS_PX * k
            strokePaint.color = Color.BLACK; strokePaint.strokeWidth = LegacyPathStyle.OUTER_OUTLINE_PX * k
            canvas.drawCircle(m.startX, m.startY, radius, strokePaint)
            strokePaint.color = classicOuter.firstOrNull() ?: Color.WHITE; strokePaint.strokeWidth = LegacyPathStyle.OUTER_COLOR_PX * k
            canvas.drawCircle(m.startX, m.startY, radius, strokePaint)
            strokePaint.color = Color.BLACK; strokePaint.strokeWidth = LegacyPathStyle.INNER_OUTLINE_PX * k
            canvas.drawCircle(m.startX, m.startY, radius, strokePaint)
            strokePaint.color = classicInner.firstOrNull() ?: Color.RED; strokePaint.strokeWidth = LegacyPathStyle.INNER_COLOR_PX * k
            canvas.drawCircle(m.startX, m.startY, radius, strokePaint)
            return
        }
        if (style.theme == PathPreviewTheme.CURRENT_GRADIENT) {
            fillPaint.color = Color.GREEN; canvas.drawCircle(m.startX, m.startY, r, fillPaint)
            if (progress >= 1f) { fillPaint.color = Color.RED; canvas.drawCircle(m.endX, m.endY, r, fillPaint) }
            return
        }
        strokePaint.color = Color.BLACK; strokePaint.strokeWidth = m.lineWidth * 0.45f
        fillPaint.color = Color.parseColor("#22C55E")
        canvas.drawCircle(m.startX, m.startY, r, fillPaint); canvas.drawCircle(m.startX, m.startY, r, strokePaint)
        canvas.drawText("起", m.startX, m.startY + textPaint.textSize * 0.36f, textPaint)
        if (progress >= 1f) {
            fillPaint.color = Color.parseColor("#EF4444")
            canvas.drawCircle(m.endX, m.endY, r, fillPaint); canvas.drawCircle(m.endX, m.endY, r, strokePaint)
            canvas.drawText("終", m.endX, m.endY + textPaint.textSize * 0.36f, textPaint)
        }
    }
}
