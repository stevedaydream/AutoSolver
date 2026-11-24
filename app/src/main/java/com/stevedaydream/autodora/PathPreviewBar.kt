package com.stevedaydream.autodora

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 路線預覽控制列：獨立、可點擊的小懸浮窗，放在螢幕上方、盤面之外。
 * 路徑畫布在另一個 FLAG_NOT_TOUCHABLE 視窗，兩者分層，不阻擋遊戲觸控。
 */
class PathPreviewBar(
    private val context: Context,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
    private val onToggleVisible: () -> Unit,
    private val onPlay: () -> Unit,
    private val onExecute: () -> Unit,
    private val onClose: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private lateinit var btnPrev: TextView
    private lateinit var btnNext: TextView
    private lateinit var title: TextView
    private lateinit var metrics: TextView
    private lateinit var shapes: TextView
    private lateinit var btnVisible: TextView
    private lateinit var btnPlay: TextView
    private lateinit var btnExecute: TextView

    val isShowing: Boolean get() = root?.isAttachedToWindow == true

    private fun build(): LinearLayout {
        val dp = { v: Float -> OverlayUi.dp(context, v) }
        btnPrev = OverlayUi.iconButton(context, "◀", OverlayUi.TEXT_HI, sizeDp = 36f, textSizeSp = 14f).apply {
            contentDescription = "上一條路線"; setOnClickListener { onPrevious() }
        }
        btnNext = OverlayUi.iconButton(context, "▶", OverlayUi.TEXT_HI, sizeDp = 36f, textSizeSp = 14f).apply {
            contentDescription = "下一條路線"; setOnClickListener { onNext() }
        }
        title = TextView(context).apply {
            setTextColor(Color.parseColor(OverlayUi.TEXT_HI)); textSize = 14f
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(8f), 0, dp(8f), 0)
        }
        val close = OverlayUi.iconButton(context, "✕", OverlayUi.TEXT_LO, sizeDp = 32f, textSizeSp = 13f).apply {
            contentDescription = "關閉路線預覽"; setOnClickListener { onClose() }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(btnPrev); addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnNext); addView(close)
        }
        metrics = TextView(context).apply {
            setTextColor(Color.parseColor(OverlayUi.VALUE)); textSize = 12f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(0, dp(2f), 0, dp(2f))
        }
        shapes = TextView(context).apply {
            setTextColor(Color.parseColor(OverlayUi.TEXT_LO)); textSize = 12f; gravity = Gravity.CENTER
            visibility = View.GONE
        }
        btnVisible = OverlayUi.pillButton(context, "隱藏").apply { setOnClickListener { onToggleVisible() } }
        btnPlay = OverlayUi.pillButton(context, "▶ 預演").apply { setOnClickListener { onPlay() } }
        btnExecute = OverlayUi.pillButton(context, "✓ 執行", textColor = OverlayUi.ACCENT,
            chipColor = OverlayUi.ACCENT_CHIP, bold = true).apply { setOnClickListener { onExecute() } }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, dp(4f), 0, 0)
            val lp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(3f); marginEnd = dp(3f) } }
            addView(btnVisible, lp()); addView(btnPlay, lp()); addView(btnExecute, lp())
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = OverlayUi.rounded(context, OverlayUi.SURFACE_CARD, 16f, stroked = true)
            setPadding(dp(10f), dp(6f), dp(10f), dp(8f))
            addView(header); addView(metrics); addView(shapes); addView(actions)
        }
    }

    /** @param boardTopY 盤面頂端螢幕座標：控制列放在盤面正上方，不遮珠子也不壓到懸浮選單。 */
    fun render(state: PathPreviewState, goals: List<ShapeGoal>, boardTopY: Int? = null,
               clearTypes: Set<BoardAnalyzer.OrbType> = emptySet()) {
        val view = root ?: build().also { root = it }
        title.text = state.label
        setEnabled(btnPrev, state.hasPrevious)
        setEnabled(btnNext, state.hasNext)
        val selected = state.selected
        metrics.text = if (selected == null) state.message.ifEmpty { "沒有可執行的路線" } else
            "首消 ${selected.firstWaveCombos}C · 模擬 ${selected.deterministicCombos}C · ${selected.stepCount} 步"
        val shapeText = listOf(shapeSummary(selected, goals, state), clearSummary(selected, clearTypes))
            .filter { it.isNotEmpty() }.joinToString("\n")
        shapes.text = shapeText
        shapes.visibility = if (shapeText.isEmpty()) View.GONE else View.VISIBLE
        btnVisible.text = if (state.visible) "隱藏" else "顯示"
        btnPlay.text = if (state.playing) "❚❚ 暫停" else if (state.progress < 1f) "▶ 繼續" else "▶ 預演"
        setEnabled(btnVisible, selected != null)
        setEnabled(btnPlay, selected != null)
        setEnabled(btnExecute, selected?.executable == true)
        val p = params()
        if (boardTopY != null) {
            view.measure(View.MeasureSpec.makeMeasureSpec(context.resources.displayMetrics.widthPixels, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            p.y = maxOf(p.y, boardTopY - view.measuredHeight - OverlayUi.dp(context, 12f))
        }
        if (!view.isAttachedToWindow) windowManager.addView(view, p)
        else if ((view.layoutParams as? WindowManager.LayoutParams)?.y != p.y) windowManager.updateViewLayout(view, p)
    }

    /** 首批全消：達成顯示 ✓，未達成顯示剩餘顆數。 */
    private fun clearSummary(candidate: PathCandidate?, types: Set<BoardAnalyzer.OrbType>): String {
        if (types.isEmpty()) return ""
        val name = ClearGoal.describe(types)
        if (candidate == null) return "首批全消 $name：未達成"
        return if (candidate.clearVerified) "首批全消 $name ✓" else "首批全消 $name ✗（剩 ${candidate.clearRemaining} 顆）"
    }

    private fun shapeSummary(candidate: PathCandidate?, goals: List<ShapeGoal>, state: PathPreviewState): String {
        if (goals.isEmpty()) return ""
        if (candidate == null) return "形狀：${state.shapeStatus?.label ?: "未達成"}"
        val parts = goals.mapIndexed { index, goal ->
            val ok = candidate.shapeMatches.any { it.goalIndex == index }
            "${orbLabel(goal.type)} ${goal.pattern.label} ${if (ok) "✓" else "✗"}"
        }
        return "形狀 ${candidate.shapeSatisfied}/${candidate.shapeRequired}：" + parts.joinToString("／")
    }

    private fun orbLabel(type: BoardAnalyzer.OrbType?) = when (type) {
        BoardAnalyzer.OrbType.WATER -> "水"; BoardAnalyzer.OrbType.FIRE -> "火"; BoardAnalyzer.OrbType.WOOD -> "木"
        BoardAnalyzer.OrbType.LIGHT -> "光"; BoardAnalyzer.OrbType.DARK -> "暗"; BoardAnalyzer.OrbType.HEART -> "心"
        else -> "任意"
    }

    private fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.35f
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        y = (if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0) + OverlayUi.dp(context, 8f)
    }

    fun hide() {
        root?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
    }
}
