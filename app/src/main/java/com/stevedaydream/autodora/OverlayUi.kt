package com.stevedaydream.autodora

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 懸浮視窗共用設計語彙：深石墨極簡風。
 * 單一薄荷綠 accent 表主要動作、紅表停止/刪除、琥珀表數值；其餘一律灰階。
 */
object OverlayUi {
    const val SURFACE      = "#F50D1117"  // 主底（深石墨，96% 不透明）
    const val SURFACE_CARD = "#F5121A23"  // 面板/卡片底
    const val CHIP         = "#14FFFFFF"  // 按鈕底（8% 白）
    const val CHIP_PRESSED = "#30FFFFFF"  // 按下態（19% 白）
    const val ACCENT_CHIP  = "#1F4ADE80"  // accent 按鈕底（12% 薄荷綠）
    const val STROKE       = "#1FFFFFFF"  // 邊框（12% 白）
    const val TEXT_HI      = "#F2F7FAFC"  // 主要文字
    const val TEXT_LO      = "#99AEB9C4"  // 次要文字
    const val ACCENT       = "#4ADE80"    // 主要動作（執行/播放）
    const val DANGER       = "#FF6B6B"    // 停止/刪除
    const val VALUE        = "#FBBF24"    // 可調數值（琥珀）

    fun dp(context: Context, v: Float): Int = (v * context.resources.displayMetrics.density).toInt()

    /** 圓角矩形底：面板、pill、卡片共用 */
    fun rounded(context: Context, color: String, radiusDp: Float, stroked: Boolean = false) =
        GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = dp(context, radiusDp).toFloat()
            if (stroked) setStroke(1, Color.parseColor(STROKE))
        }

    /** 正圓底 */
    fun circle(context: Context, color: String, stroked: Boolean = false) =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(color))
            if (stroked) setStroke(dp(context, 1f).coerceAtLeast(1), Color.parseColor(STROKE))
        }

    /** 帶按下態的正圓底 */
    fun chipCircle(context: Context, base: String = CHIP): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), circle(context, CHIP_PRESSED))
            addState(intArrayOf(), circle(context, base))
        }

    /** 帶按下態的圓角矩形底 */
    fun chipRounded(context: Context, radiusDp: Float, base: String = CHIP): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(context, CHIP_PRESSED, radiusDp))
            addState(intArrayOf(), rounded(context, base, radiusDp))
        }

    /** 圓形圖示按鈕（選單列、列表操作共用） */
    fun iconButton(
        context: Context,
        glyph: String,
        tint: String = TEXT_HI,
        sizeDp: Float = 40f,
        textSizeSp: Float = 16f,
        chipColor: String = CHIP
    ): TextView = TextView(context).apply {
        text = glyph
        setTextColor(Color.parseColor(tint))
        textSize = textSizeSp
        gravity = Gravity.CENTER
        includeFontPadding = false
        val s = dp(context, sizeDp)
        layoutParams = LinearLayout.LayoutParams(s, s).apply {
            marginStart = dp(context, 2f); marginEnd = dp(context, 2f)
            gravity = Gravity.CENTER_VERTICAL
        }
        background = chipCircle(context, chipColor)
    }

    /** 步進器的 −/＋ 小圓鈕 */
    fun stepButton(context: Context, glyph: String): TextView =
        iconButton(context, glyph, tint = TEXT_HI, sizeDp = 30f, textSizeSp = 15f)

    /** 步進器中央數值：等寬字、固定寬度避免跳動 */
    fun valueText(context: Context, initial: String, widthDp: Float = 48f): TextView =
        TextView(context).apply {
            text = initial
            setTextColor(Color.parseColor(VALUE))
            textSize = 13f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(context, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    /** 文字 pill 按鈕（設定面板工具、清單主按鈕共用） */
    fun pillButton(
        context: Context,
        label: String,
        textColor: String = TEXT_HI,
        chipColor: String = CHIP,
        bold: Boolean = false
    ): TextView = TextView(context).apply {
        text = label
        setTextColor(Color.parseColor(textColor))
        textSize = 13f
        if (bold) setTypeface(Typeface.DEFAULT_BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(context, 14f), dp(context, 9f), dp(context, 14f), dp(context, 9f))
        background = chipRounded(context, 20f, chipColor)
    }

    /** 分組小標（大寫字距風格的段落標籤） */
    fun sectionLabel(context: Context, label: String): TextView = TextView(context).apply {
        text = label
        setTextColor(Color.parseColor(TEXT_LO))
        textSize = 11f
        letterSpacing = 0.12f
        setTypeface(Typeface.DEFAULT_BOLD)
        setPadding(0, dp(context, 14f), 0, dp(context, 6f))
    }

    /** 選單列內的直向細分隔線 */
    fun vDivider(context: Context): View = View(context).apply {
        setBackgroundColor(Color.parseColor(STROKE))
        layoutParams = LinearLayout.LayoutParams(1, dp(context, 18f)).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = dp(context, 6f); marginEnd = dp(context, 6f)
        }
    }
}
