package com.stevedaydream.autodora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast

class RecognitionOverlayWindow(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    fun show(
        board: Array<Array<BoardAnalyzer.OrbType>>,
        features: Array<Array<BoardAnalyzer.CellFeatures>>,
        startX: Int, startY: Int, orbSize: Int
    ) {
        hide()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
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

        overlayView = OrbOverlayView(context, board, features, startX, startY, orbSize) { hide() }
        windowManager.addView(overlayView, params)
    }

    fun hide() {
        overlayView?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
            overlayView = null
        }
    }
}

// ─────────────────────────────────────────────────────────
// 自訂 View：負責繪製盤面、選擇器，以及處理所有觸控
// ─────────────────────────────────────────────────────────
@SuppressLint("ViewConstructor")
private class OrbOverlayView(
    context: Context,
    board: Array<Array<BoardAnalyzer.OrbType>>,
    private val features: Array<Array<BoardAnalyzer.CellFeatures>>,
    private val startX: Int,
    private val startY: Int,
    private val orbSize: Int,
    private val onClose: () -> Unit
) : View(context) {

    // 可變盤面（修正後即時更新顯示）
    private val mutableBoard = Array(5) { r -> Array(6) { c -> board[r][c] } }

    // 選擇器狀態（-1 = 未開啟）
    private var pickerRow = -1
    private var pickerCol = -1

    // 選擇器中暫存的狀態選擇（預設 NONE）
    private var selectedStatus = BoardAnalyzer.OrbStatus.NONE

    // 屬性清單（選擇器顯示順序）
    private val orbTypes = arrayOf(
        BoardAnalyzer.OrbType.FIRE,
        BoardAnalyzer.OrbType.WATER,
        BoardAnalyzer.OrbType.WOOD,
        BoardAnalyzer.OrbType.LIGHT,
        BoardAnalyzer.OrbType.DARK,
        BoardAnalyzer.OrbType.HEART,
        BoardAnalyzer.OrbType.UNKNOWN
    )

    // 特效狀態清單（選擇器第二排）
    private val statusTypes = arrayOf(
        BoardAnalyzer.OrbStatus.NONE,
        BoardAnalyzer.OrbStatus.BURN,
        BoardAnalyzer.OrbStatus.SHIELD,
        BoardAnalyzer.OrbStatus.POISON,
        BoardAnalyzer.OrbStatus.STICKY,
        BoardAnalyzer.OrbStatus.FREEZE,
        BoardAnalyzer.OrbStatus.WEATHER
    )

    // 屬性對應色彩（實心，選擇器用）
    private val solidColors = mapOf(
        BoardAnalyzer.OrbType.FIRE    to Color.parseColor("#FF4C4C"),
        BoardAnalyzer.OrbType.WATER   to Color.parseColor("#2196F3"),
        BoardAnalyzer.OrbType.WOOD    to Color.parseColor("#4CAF50"),
        BoardAnalyzer.OrbType.LIGHT   to Color.parseColor("#FFEB3B"),
        BoardAnalyzer.OrbType.DARK    to Color.parseColor("#9C27B0"),
        BoardAnalyzer.OrbType.HEART   to Color.parseColor("#E91E63"),
        BoardAnalyzer.OrbType.UNKNOWN to Color.parseColor("#9E9E9E")
    )

    // 屬性對應半透明色彩（盤面格子用）
    private val alphaColors = solidColors.mapValues { (_, c) ->
        Color.argb(0x99, Color.red(c), Color.green(c), Color.blue(c))
    }

    private val orbLabels = mapOf(
        BoardAnalyzer.OrbType.FIRE    to "火",
        BoardAnalyzer.OrbType.WATER   to "水",
        BoardAnalyzer.OrbType.WOOD    to "木",
        BoardAnalyzer.OrbType.LIGHT   to "光",
        BoardAnalyzer.OrbType.DARK    to "暗",
        BoardAnalyzer.OrbType.HEART   to "心",
        BoardAnalyzer.OrbType.UNKNOWN to "?"
    )

    // 特效狀態色彩與標籤
    private val statusColors = mapOf(
        BoardAnalyzer.OrbStatus.NONE    to Color.parseColor("#757575"),
        BoardAnalyzer.OrbStatus.BURN    to Color.parseColor("#FF6D00"),
        BoardAnalyzer.OrbStatus.SHIELD  to Color.parseColor("#00BCD4"),
        BoardAnalyzer.OrbStatus.POISON  to Color.parseColor("#6A1B9A"),
        BoardAnalyzer.OrbStatus.STICKY  to Color.parseColor("#8D6E63"),
        BoardAnalyzer.OrbStatus.FREEZE  to Color.parseColor("#4FC3F7"),
        BoardAnalyzer.OrbStatus.WEATHER to Color.parseColor("#78909C")
    )
    private val statusLabels = mapOf(
        BoardAnalyzer.OrbStatus.NONE    to "無",
        BoardAnalyzer.OrbStatus.BURN    to "燃燒",
        BoardAnalyzer.OrbStatus.SHIELD  to "加護",
        BoardAnalyzer.OrbStatus.POISON  to "腐化",
        BoardAnalyzer.OrbStatus.STICKY  to "黏著",
        BoardAnalyzer.OrbStatus.FREEZE  to "冰凍",
        BoardAnalyzer.OrbStatus.WEATHER to "風化"
    )

    // 畫筆
    private val fillPaint   = Paint().apply { style = Paint.Style.FILL;   isAntiAlias = true }
    private val strokePaint = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = true; color = Color.WHITE }
    private val bgPaint     = Paint().apply { style = Paint.Style.FILL;   color = Color.parseColor("#DD000000") }
    private val textPaint   = Paint().apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isAntiAlias = true
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val hintPaint   = Paint().apply {
        color = Color.parseColor("#CCFFFFFF"); textAlign = Paint.Align.CENTER; isAntiAlias = true
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    // ── 選擇器佈局（全部依螢幕寬度推算，避免溢出）──────────
    private data class PickerLayout(
        val top: Float,       val left: Float,
        val w: Float,         val h: Float,
        val gap: Float,       val btnR: Float,
        val typeRowCy: Float, val statusRowCy: Float,
        val pillW: Float,     val pillH: Float,
        val titleY: Float,    val sepLabelY: Float
    )

    /** 每次繪製 / 觸控前呼叫，確保與目前螢幕尺寸同步 */
    private fun computePickerLayout(): PickerLayout {
        val sw  = width.toFloat().coerceAtLeast(1f)
        val sh  = height.toFloat().coerceAtLeast(1f)
        val pad = sw * 0.025f                                // 左右留白
        val w   = sw - pad * 2f                             // picker 實際寬度
        val gap = w / orbTypes.size                         // 每個按鈕佔用寬度
        val bR  = (gap * 0.40f).coerceAtMost(sh * 0.055f)  // 圓半徑，上限 = 5.5% 螢幕高

        val pillH = bR * 0.78f
        val pillW = gap * 0.84f

        val topMargin    = sh * 0.015f          // 頂部安全邊距
        val titleH       = gap * 0.30f          // 標題文字高度空間
        val typeOffset   = titleH + bR * 1.65f  // 屬性排圓心 距 pickerTop 的偏移
        val sepLabelOff  = typeOffset + bR * 1.20f
        val statusOffset = sepLabelOff + pillH * 0.55f + pillH * 0.50f
        val h            = statusOffset + pillH * 0.70f + gap * 0.12f

        return PickerLayout(
            top         = topMargin,
            left        = pad,
            w           = w,
            h           = h,
            gap         = gap,
            btnR        = bR,
            typeRowCy   = topMargin + typeOffset,
            statusRowCy = topMargin + statusOffset,
            pillW       = pillW,
            pillH       = pillH,
            titleY      = topMargin + titleH,
            sepLabelY   = topMargin + sepLabelOff
        )
    }

    // ── 繪製 ────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        val cellR = orbSize / 2.2f
        textPaint.textSize = orbSize * 0.4f

        for (row in 0 until 5) {
            for (col in 0 until 6) {
                val type = mutableBoard[row][col]
                val cx = startX + col * orbSize + orbSize / 2f
                val cy = startY + row * orbSize + orbSize / 2f

                // 選中格高亮外圈
                if (row == pickerRow && col == pickerCol) {
                    strokePaint.strokeWidth = orbSize * 0.08f
                    canvas.drawCircle(cx, cy, cellR * 1.12f, strokePaint)
                }

                // 半透明填色圓
                fillPaint.color = alphaColors[type] ?: Color.GRAY
                canvas.drawCircle(cx, cy, cellR, fillPaint)

                // 屬性文字
                val textOffset = (textPaint.descent() + textPaint.ascent()) / 2
                canvas.drawText(orbLabels[type] ?: "?", cx, cy - textOffset, textPaint)
            }
        }

        // 提示文字（盤面上方）
        hintPaint.textSize = orbSize * 0.26f
        canvas.drawText(
            "點格子修正屬性・格子外關閉",
            width / 2f,
            (startY - orbSize * 0.35f).coerceAtLeast(orbSize * 0.5f),
            hintPaint
        )

        // 選擇器彈出框
        if (pickerRow >= 0) drawPicker(canvas)
    }

    private fun drawPicker(canvas: Canvas) {
        val lp = computePickerLayout()
        val sw = width.toFloat()

        // ── 背景框（頂部固定，依內容高度延伸）────────────────
        canvas.drawRoundRect(
            RectF(
                lp.left - lp.gap * 0.14f,
                lp.top  - lp.gap * 0.10f,
                lp.left + lp.w + lp.gap * 0.14f,
                lp.top  + lp.h
            ),
            24f, 24f, bgPaint
        )

        // ── 標題 ───────────────────────────────────────────────
        hintPaint.textSize = lp.gap * 0.24f
        canvas.drawText("選擇正確屬性", sw / 2f, lp.titleY, hintPaint)

        // ── 第一排：屬性圓按鈕 ──────────────────────────────
        textPaint.textSize = lp.btnR * 0.88f
        for ((idx, type) in orbTypes.withIndex()) {
            val bx = lp.left + idx * lp.gap + lp.gap / 2f

            if (type == mutableBoard[pickerRow][pickerCol]) {
                strokePaint.strokeWidth = lp.btnR * 0.18f
                canvas.drawCircle(bx, lp.typeRowCy, lp.btnR * 1.28f, strokePaint)
            }
            fillPaint.color = solidColors[type] ?: Color.GRAY
            canvas.drawCircle(bx, lp.typeRowCy, lp.btnR, fillPaint)

            val tOff = (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(orbLabels[type] ?: "?", bx, lp.typeRowCy - tOff, textPaint)
        }

        // ── 分隔標籤 ────────────────────────────────────────────
        hintPaint.textSize = lp.gap * 0.18f
        canvas.drawText("特效狀態（選後再點屬性確認）", sw / 2f, lp.sepLabelY, hintPaint)

        // ── 第二排：狀態藥丸 ────────────────────────────────────
        textPaint.textSize = lp.pillH * 0.58f
        for ((idx, status) in statusTypes.withIndex()) {
            val bx  = lp.left + idx * lp.gap + lp.gap / 2f
            val col = statusColors[status] ?: Color.GRAY
            val pill = RectF(
                bx - lp.pillW / 2f, lp.statusRowCy - lp.pillH / 2f,
                bx + lp.pillW / 2f, lp.statusRowCy + lp.pillH / 2f
            )
            val r = lp.pillH / 2f

            if (status == selectedStatus) {
                fillPaint.color = col
                canvas.drawRoundRect(pill, r, r, fillPaint)
                strokePaint.strokeWidth = lp.btnR * 0.14f
                canvas.drawRoundRect(pill, r, r, strokePaint)
            } else {
                fillPaint.color = Color.argb(0x88, Color.red(col), Color.green(col), Color.blue(col))
                canvas.drawRoundRect(pill, r, r, fillPaint)
            }

            val tOff = (textPaint.descent() + textPaint.ascent()) / 2
            textPaint.color = if (status == BoardAnalyzer.OrbStatus.FREEZE) Color.BLACK else Color.WHITE
            canvas.drawText(statusLabels[status] ?: "", bx, lp.statusRowCy - tOff, textPaint)
            textPaint.color = Color.WHITE
        }
    }

    // ── 觸控 ────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return true   // 必須 true 才能收到 UP
            MotionEvent.ACTION_UP   -> handleTap(event.x, event.y)
        }
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        if (pickerRow >= 0) {
            val lp = computePickerLayout()

            // ① 偵測狀態藥丸點擊（不關閉選擇器）
            val extra = lp.pillH * 0.25f
            for ((idx, status) in statusTypes.withIndex()) {
                val bx = lp.left + idx * lp.gap + lp.gap / 2f
                if (Math.abs(x - bx) <= lp.pillW / 2f + extra &&
                    Math.abs(y - lp.statusRowCy) <= lp.pillH / 2f + extra) {
                    selectedStatus = status
                    invalidate()
                    return   // 保持選擇器開啟
                }
            }

            // ② 偵測屬性圓按鈕點擊（確認並關閉）
            for ((idx, type) in orbTypes.withIndex()) {
                val bx = lp.left + idx * lp.gap + lp.gap / 2f
                val dx = x - bx; val dy = y - lp.typeRowCy
                if (dx * dx + dy * dy <= (lp.btnR * 1.4f) * (lp.btnR * 1.4f)) {
                    saveAndUpdate(pickerRow, pickerCol, type, selectedStatus)
                    break
                }
            }

            // ③ 無論是否點中屬性按鈕，都關閉選擇器並重置狀態
            pickerRow = -1; pickerCol = -1
            selectedStatus = BoardAnalyzer.OrbStatus.NONE
            invalidate()
        } else {
            // 一般狀態：偵測是否點到格子
            val row = ((y - startY) / orbSize).toInt()
            val col = ((x - startX) / orbSize).toInt()
            if (row in 0 until 5 && col in 0 until 6) {
                val cx = startX + col * orbSize + orbSize / 2f
                val cy = startY + row * orbSize + orbSize / 2f
                val dx = x - cx; val dy = y - cy
                val r = orbSize / 2.2f
                if (dx * dx + dy * dy <= r * r) {
                    pickerRow = row; pickerCol = col
                    invalidate()
                    return
                }
            }
            // 點在格子外 → 關閉整個疊層
            onClose()
        }
    }

    // ── 存樣本 ──────────────────────────────────────────────
    private fun saveAndUpdate(
        row: Int, col: Int,
        selectedType: BoardAnalyzer.OrbType,
        status: BoardAnalyzer.OrbStatus
    ) {
        val oldType = mutableBoard[row][col]
        val variant = when {
            status != BoardAnalyzer.OrbStatus.NONE -> 'D'   // 特效珠
            selectedType == oldType                -> 'B'   // 演算法正確辨識
            else                                   -> 'A'   // 演算法誤判，人工修正
        }
        val feat = features[row][col]

        val evicted = RecognitionDatabase.instance?.addSample(
            RecognitionDatabase.Sample(
                d290    = feat.d290,  d280 = feat.d280, d300 = feat.d300,
                w130    = feat.w130,  l50  = feat.l50,  wt210= feat.wt210,
                h310    = feat.h310,  h320 = feat.h320, f020 = feat.f020,
                validPx = feat.validPx,
                type    = selectedType,
                variant = variant,
                status  = status
            )
        ) ?: 0
        if (evicted > 0) {
            Toast.makeText(context, "已自動淘汰 $evicted 筆舊樣本（資料庫已達上限）", Toast.LENGTH_SHORT).show()
        }

        mutableBoard[row][col] = selectedType
        invalidate()
    }

}
