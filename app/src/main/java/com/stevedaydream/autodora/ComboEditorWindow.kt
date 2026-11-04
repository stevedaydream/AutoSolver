package com.stevedaydream.autodora

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 組合清單編輯器：命名、加入巨集、調整順序與每步重複次數 */
class ComboEditorWindow(
    private val context: Context,
    private val original: MacroCombo?,
    private val onClose: () -> Unit
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dp = context.resources.displayMetrics.density
    private var steps = original?.steps.orEmpty()
    private val macros = MacroStorage.listAll(context)
    private val macroNames = macros.associate { it.id to it.name }
    private var root: LinearLayout? = null
    private lateinit var nameField: EditText
    private lateinit var stepRows: LinearLayout
    private lateinit var summary: TextView

    private fun px(v: Float) = (v * dp).toInt()

    fun show() {
        val metrics = android.util.DisplayMetrics().also {
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it)
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = OverlayUi.rounded(context, OverlayUi.SURFACE, 20f, stroked = true)
            setPadding(px(14f), px(12f), px(14f), px(14f))
        }

        // 標題列
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = if (original == null) "新增組合" else "編輯組合"
            setTextColor(Color.parseColor(OverlayUi.TEXT_HI)); textSize = 15f
            setTypeface(Typeface.DEFAULT_BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(OverlayUi.pillButton(context, "取消", textColor = OverlayUi.TEXT_LO).apply {
            setOnClickListener { close() }
        })
        header.addView(OverlayUi.pillButton(context, "儲存", textColor = OverlayUi.ACCENT, bold = true).apply {
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(6f) })
        layout.addView(header)

        // 名稱
        nameField = EditText(context).apply {
            setText(original?.name ?: "組合_${System.currentTimeMillis() % 100000}")
            setSingleLine()
            setTextColor(Color.parseColor(OverlayUi.TEXT_HI)); textSize = 14f
            hint = "組合名稱"; setHintTextColor(Color.parseColor(OverlayUi.TEXT_LO))
            background = OverlayUi.rounded(context, OverlayUi.CHIP, 12f)
            setPadding(px(12f), px(9f), px(12f), px(9f))
        }
        layout.addView(nameField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })

        // 步驟
        val stepHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
        }
        stepHeader.addView(OverlayUi.sectionLabel(context, "執行順序"),
            LinearLayout.LayoutParams(0, -2, 1f))
        summary = TextView(context).apply {
            setTextColor(Color.parseColor(OverlayUi.TEXT_LO)); textSize = 11f
            setPadding(0, 0, 0, px(6f))
        }
        stepHeader.addView(summary)
        layout.addView(stepHeader)
        val stepScroll = ScrollView(context)
        stepRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        stepScroll.addView(stepRows)
        layout.addView(stepScroll, LinearLayout.LayoutParams(-1, 0, 1.3f))

        // 可加入的巨集
        layout.addView(OverlayUi.sectionLabel(context, "點選加入巨集"))
        val pickScroll = ScrollView(context)
        val pickRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        pickScroll.addView(pickRows)
        if (macros.isEmpty()) {
            pickRows.addView(TextView(context).apply {
                text = "尚無巨集，請先錄製"
                setTextColor(Color.parseColor(OverlayUi.TEXT_LO)); textSize = 13f
                gravity = Gravity.CENTER; setPadding(0, px(12f), 0, px(12f))
            })
        }
        macros.forEach { macro ->
            pickRows.addView(TextView(context).apply {
                text = "＋  ${macro.name}"
                setTextColor(Color.parseColor(OverlayUi.TEXT_HI)); textSize = 13f
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(px(12f), px(9f), px(12f), px(9f))
                background = OverlayUi.chipRounded(context, 12f)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(4f) }
                setOnClickListener {
                    steps = steps + ComboStep(macro.id)
                    refresh()
                    stepScroll.post { stepScroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            })
        }
        layout.addView(pickScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val params = WindowManager.LayoutParams(
            (metrics.widthPixels * .92f).toInt(), (metrics.heightPixels * .8f).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER; dimAmount = .6f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        wm.addView(layout, params)
        root = layout
        refresh()
    }

    private fun refresh() {
        stepRows.removeAllViews()
        val missing = ComboPlan.missingSteps(steps, macroNames.keys).toSet()
        summary.text = if (steps.isEmpty()) "" else
            "${steps.size} 步 · 每輪 ${ComboPlan.runsPerRound(steps)} 次"
        if (steps.isEmpty()) {
            stepRows.addView(TextView(context).apply {
                text = "從下方點選巨集加入組合"
                setTextColor(Color.parseColor(OverlayUi.TEXT_LO)); textSize = 13f
                gravity = Gravity.CENTER; setPadding(0, px(16f), 0, px(16f))
            })
            return
        }
        steps.forEachIndexed { index, step ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = OverlayUi.rounded(context, OverlayUi.CHIP, 12f)
                setPadding(px(10f), px(4f), px(4f), px(4f))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(4f) }
            }
            row.addView(TextView(context).apply {
                text = "${index + 1}. " + (macroNames[step.macroId] ?: "（巨集已刪除）")
                setTextColor(Color.parseColor(if (index in missing) OverlayUi.DANGER else OverlayUi.TEXT_HI))
                textSize = 13f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(OverlayUi.iconButton(context, "−", sizeDp = 26f, textSizeSp = 13f).apply {
                setOnClickListener { update(index, step.copy(repeat = RepeatCount.decStep(step.repeat))) }
            })
            row.addView(OverlayUi.valueText(context, RepeatCount.label(step.repeat), widthDp = 34f))
            row.addView(OverlayUi.iconButton(context, "＋", sizeDp = 26f, textSizeSp = 13f).apply {
                setOnClickListener { update(index, step.copy(repeat = RepeatCount.incStep(step.repeat))) }
            })
            row.addView(OverlayUi.iconButton(context, "↑", sizeDp = 26f, textSizeSp = 13f).apply {
                alpha = if (index > 0) 1f else .3f
                setOnClickListener { steps = ComboPlan.move(steps, index, index - 1); refresh() }
            })
            row.addView(OverlayUi.iconButton(context, "↓", sizeDp = 26f, textSizeSp = 13f).apply {
                alpha = if (index < steps.lastIndex) 1f else .3f
                setOnClickListener { steps = ComboPlan.move(steps, index, index + 1); refresh() }
            })
            row.addView(OverlayUi.iconButton(context, "✕", OverlayUi.DANGER, sizeDp = 26f, textSizeSp = 12f).apply {
                setOnClickListener { steps = steps.filterIndexed { i, _ -> i != index }; refresh() }
            })
            stepRows.addView(row)
        }
    }

    private fun update(index: Int, step: ComboStep) {
        steps = steps.toMutableList().also { it[index] = step }
        refresh()
    }

    private fun save() {
        val name = nameField.text.toString().trim()
        when {
            name.isBlank() -> { nameField.error = "請輸入名稱"; return }
            steps.isEmpty() -> { toast("請至少加入一個巨集"); return }
        }
        try {
            ComboStorage.save(context, MacroCombo(
                id = original?.id ?: MacroStorage.newId(),
                name = name,
                steps = steps,
                createdAt = original?.createdAt ?: System.currentTimeMillis()
            ))
            close()
        } catch (e: Exception) { toast("儲存失敗：${e.message}") }
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    private fun close() { dismiss(); onClose() }

    fun dismiss() {
        root?.let { if (it.isAttachedToWindow) wm.removeView(it) }
        root = null
    }
}
