package com.stevedaydream.autodora

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class MacroGestureView(context: Context) : View(context) {
    var onEdit: ((MacroAction) -> Unit)? = null
    var redrawPath = false
    var editable = true
    /** 每個點擊各自對照的版面方塊與判讀結果（索引對應 points()） */
    var guides: List<Pair<List<LayoutBox>, TeamLayout.TapHit>> = emptyList()
        set(value) { field = value; invalidate() }
    private var action: MacroAction? = null
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private var screenWidth = 1f
    private var screenHeight = 1f
    private var progress = 1f
    private var animator: ValueAnimator? = null
    private var dragIndex = -1
    private var draft = mutableListOf<Pair<Float, Float>>()

    fun display(value: MacroAction, width: Int, height: Int, frame: MacroPreview? = value.preview) {
        stop()
        action = value
        bitmap?.recycle()
        bitmap = frame?.let { MacroImages.file(context, it.image) }?.let {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(it.path, options)
            options.inSampleSize = (options.outHeight / 900).coerceAtLeast(1)
            options.inJustDecodeBounds = false
            BitmapFactory.decodeFile(it.path, options)
        }
        screenWidth = (frame?.width ?: width).coerceAtLeast(1).toFloat()
        screenHeight = (frame?.height ?: height).coerceAtLeast(1).toFloat()
        invalidate()
    }

    fun animate(duration: Long) {
        stop()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration.coerceAtLeast(1)
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun stop() { animator?.cancel(); animator = null; progress = 1f; invalidate() }
    fun seek(fraction: Float) { progress = fraction.coerceIn(0f, 1f); invalidate() }
    fun release() { stop(); bitmap?.recycle(); bitmap = null }

    private fun points(): List<Pair<Float, Float>> = when (val a = action) {
        is MacroAction.Tap -> listOf(a.x to a.y)
        is MacroAction.Swipe -> a.path
        is MacroAction.FixedBoard -> a.path
        is MacroAction.UseSkill -> if (a.hasStage2) listOf(a.tapX to a.tapY, a.stage2X to a.stage2Y)
            else listOf(a.tapX to a.tapY)
        else -> emptyList()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(8, 14, 20))
        val scale = minOf(width / screenWidth, height / screenHeight)
        val w = screenWidth * scale
        val h = screenHeight * scale
        bounds.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        paint.style = Paint.Style.FILL
        bitmap?.let { canvas.drawBitmap(it, null, bounds, paint) }
        if (bitmap == null) {
            paint.color = Color.rgb(40, 53, 66); paint.strokeWidth = 1f
            for (i in 0..6) {
                val x = bounds.left + bounds.width() * i / 6
                val y = bounds.top + bounds.height() * i / 6
                canvas.drawLine(x, bounds.top, x, bounds.bottom, paint)
                canvas.drawLine(bounds.left, y, bounds.right, y, paint)
            }
            paint.color = Color.LTGRAY; paint.textSize = 12 * resources.displayMetrics.scaledDensity
            canvas.drawText("座標底圖 ${screenWidth.toInt()} × ${screenHeight.toInt()}", bounds.left + 8, bounds.top + 20, paint)
        }
        val pts = if (dragIndex >= 0) draft else points()
        fun px(pt: Pair<Float, Float>) = bounds.left + pt.first * scale
        fun py(pt: Pair<Float, Float>) = bounds.top + pt.second * scale
        drawGuides(canvas, scale, pts)
        paint.color = Color.parseColor(OverlayUi.ACCENT)
        paint.strokeWidth = 3 * resources.displayMetrics.density
        paint.style = Paint.Style.STROKE
        val isPath = action is MacroAction.Swipe || action is MacroAction.FixedBoard
        if (isPath && pts.size > 1) {
            val path = Path().apply { moveTo(px(pts.first()), py(pts.first())); pts.drop(1).forEach { lineTo(px(it), py(it)) } }
            paint.alpha = 90; canvas.drawPath(path, paint); paint.alpha = 255
            val measure = PathMeasure(path, false)
            val segment = Path()
            measure.getSegment(0f, measure.length * progress, segment, true)
            canvas.drawPath(segment, paint)
            val cursor = FloatArray(2)
            measure.getPosTan(measure.length * progress, cursor, null)
            canvas.drawCircle(cursor[0], cursor[1], 7 * resources.displayMetrics.density, paint)
        }
        val handles = if (isPath && pts.size > 1) listOf(0, pts.lastIndex) else pts.indices.toList()
        handles.forEach { i ->
            paint.alpha = if (action is MacroAction.UseSkill && pts.size > 1 && i != (if (progress < .5f) 0 else 1)) 80 else 255
            val pt = pts[i]
            paint.style = Paint.Style.STROKE
            canvas.drawCircle(px(pt), py(pt), (8 + if (!isPath) progress * 16 else 0f) * resources.displayMetrics.density, paint)
            paint.style = Paint.Style.FILL
            paint.textSize = 12 * resources.displayMetrics.scaledDensity
            val label = if (isPath) { if (i == 0) "起" else "終" } else "${i + 1}"
            val text = "$label (${pt.first.toInt()}, ${pt.second.toInt()})"
            val textX = (px(pt) + 8).coerceIn(0f, (width - paint.measureText(text)).coerceAtLeast(0f))
            val textY = (py(pt) + if (i > 0) paint.textSize + 12 else -12f).coerceIn(paint.textSize, height.toFloat())
            canvas.drawText(text, textX, textY, paint)
        }
        paint.alpha = 255
    }

    /**
     * 畫出目前點擊對照的版面方塊：命中的方塊填色並以 accent 描邊，其餘以灰色虛線；
     * 兩段技能在動畫前半顯示第一段版面、後半顯示第二段。底部列出各段判讀結果。
     */
    private fun drawGuides(canvas: Canvas, scale: Float, pts: List<Pair<Float, Float>>) {
        if (guides.isEmpty()) return
        val density = resources.displayMetrics.density
        val active = if (guides.size > 1 && progress < .5f) 0 else guides.lastIndex
        val (boxes, _) = guides[active]
        val point = pts.getOrNull(active)
        val hitBox = point?.let { TeamLayout.hit(boxes, it.first, it.second) }
        val rect = RectF()
        val dash = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
        paint.textSize = 10 * resources.displayMetrics.scaledDensity
        boxes.forEach { box ->
            rect.set(bounds.left + box.left * scale, bounds.top + box.top * scale,
                bounds.left + box.right * scale, bounds.top + box.bottom * scale)
            val hit = box == hitBox
            if (hit) {
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor(OverlayUi.ACCENT); paint.alpha = 60
                canvas.drawRoundRect(rect, 4 * density, 4 * density, paint)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (if (hit) 2.5f else 1.2f) * density
            paint.pathEffect = if (hit) null else dash
            paint.color = if (hit) Color.parseColor(OverlayUi.ACCENT) else Color.argb(170, 200, 210, 220)
            canvas.drawRoundRect(rect, 4 * density, 4 * density, paint)
            paint.pathEffect = null
            paint.style = Paint.Style.FILL
            // 方塊太窄時只顯示編號（例如「成員 6」→「6」），避免相鄰標籤重疊
            val label = if (paint.measureText(box.label) > rect.width() - 4 * density)
                box.label.substringAfterLast(' ') else box.label
            canvas.drawText(label, rect.left + 3 * density, rect.top + paint.textSize + 1 * density, paint)
        }
        // 底部判讀列：每段一行，命中為綠、未命中為紅
        paint.textSize = 12 * resources.displayMetrics.scaledDensity
        guides.forEachIndexed { i, (guideBoxes, original) ->
            // 拖曳時依即時座標重新判讀
            val hit = pts.getOrNull(i)?.let { original.copy(box = TeamLayout.hit(guideBoxes, it.first, it.second)) } ?: original
            val ok = hit.box != null
            val text = "${i + 1}. ${if (hit.screen == TeamLayout.Screen.MEMBERS) "成員列" else "技能頁"}：" +
                "${TeamLayout.describe(hit)} ${if (ok) "✓" else "✗"}"
            val y = bounds.bottom - (guides.size - 1 - i) * (paint.textSize + 6) - 8
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(200, 8, 14, 20)
            canvas.drawRect(bounds.left, y - paint.textSize - 2, bounds.left + paint.measureText(text) + 16, y + 6, paint)
            paint.color = if (ok) Color.parseColor(OverlayUi.ACCENT) else Color.parseColor(OverlayUi.DANGER)
            canvas.drawText(text, bounds.left + 8, y, paint)
        }
        paint.alpha = 255
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editable || bounds.width() <= 0) return false
        val a = action ?: return false
        val isPath = a is MacroAction.Swipe || a is MacroAction.FixedBoard
        val point = ((event.x - bounds.left) / bounds.width() * screenWidth).coerceIn(0f, screenWidth - 1) to
            ((event.y - bounds.top) / bounds.height() * screenHeight).coerceIn(0f, screenHeight - 1)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!bounds.contains(event.x, event.y) || points().isEmpty()) return false
                stop(); parent.requestDisallowInterceptTouchEvent(true)
                draft = points().toMutableList()
                dragIndex = if (isPath && redrawPath) { draft.clear(); 0 } else {
                    val indices = if (isPath) listOf(0, draft.lastIndex) else draft.indices.toList()
                    indices.minByOrNull { hypot((draft[it].first - point.first).toDouble(), (draft[it].second - point.second).toDouble()) } ?: 0
                }
                if (isPath && redrawPath) draft.add(point) else draft[dragIndex] = point
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (dragIndex < 0) return false
                if (isPath && redrawPath) draft.add(point) else draft[dragIndex] = point
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val edited = when (a) {
                        is MacroAction.Tap -> a.copy(x = point.first, y = point.second)
                        is MacroAction.Swipe -> a.copy(path = draft.toList())
                        is MacroAction.FixedBoard -> a.copy(path = draft.toList())
                        is MacroAction.UseSkill -> a.copy(tapX = draft[0].first, tapY = draft[0].second,
                            stage2X = draft.getOrElse(1) { 0f to 0f }.first, stage2Y = draft.getOrElse(1) { 0f to 0f }.second)
                        else -> a
                    }
                    edited.preview = a.preview
                    dragIndex = -1
                    parent.requestDisallowInterceptTouchEvent(false)
                    onEdit?.invoke(edited)
                }
            }
            MotionEvent.ACTION_CANCEL -> { dragIndex = -1; parent.requestDisallowInterceptTouchEvent(false) }
        }
        invalidate()
        return true
    }
}
