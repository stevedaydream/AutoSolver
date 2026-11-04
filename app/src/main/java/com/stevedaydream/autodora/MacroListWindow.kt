package com.stevedaydream.autodora

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MacroListWindow(
    private val context: Context,
    private val accessibilityService: AutoPuzzlerAccessibilityService,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootLayout: LinearLayout? = null
    private var listContainer: LinearLayout? = null
    private var editor: MacroEditorWindow? = null
    private var comboEditor: ComboEditorWindow? = null
    /** 0 = 巨集分頁，1 = 組合分頁 */
    private var tab = 0
    private var tabMacro: TextView? = null
    private var tabCombo: TextView? = null
    private var addButton: TextView? = null
    private var isPlaying = false
    @Volatile private var stopRequested = false
    private var stopBar: LinearLayout? = null
    private var stopBarLabel: TextView? = null

    fun show() {
        if (rootLayout != null) { refresh(); return }
        val dp = context.resources.displayMetrics.density

        rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = OverlayUi.rounded(context, OverlayUi.SURFACE, 20f, stroked = true)
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt())
            minimumWidth = (296 * dp).toInt()
        }

        // Title bar
        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (10 * dp).toInt())
        }
        titleBar.addView(TextView(context).apply {
            text = "巨集清單"
            setTextColor(Color.parseColor(OverlayUi.TEXT_HI))
            textSize = 15f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleBar.addView(OverlayUi.iconButton(
            context, "✕", OverlayUi.TEXT_LO, sizeDp = 30f, textSizeSp = 13f
        ).apply { setOnClickListener { hide() } })
        rootLayout!!.addView(titleBar)

        // 分頁：巨集｜組合
        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        tabMacro = OverlayUi.pillButton(context, "巨集").apply { setOnClickListener { tab = 0; refresh() } }
        tabCombo = OverlayUi.pillButton(context, "組合").apply { setOnClickListener { tab = 1; refresh() } }
        tabRow.addView(tabMacro, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = (3 * dp).toInt() })
        tabRow.addView(tabCombo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = (3 * dp).toInt() })
        rootLayout!!.addView(tabRow)

        // Scrollable macro list
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (300 * dp).toInt()
            )
        }
        listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(listContainer)
        rootLayout!!.addView(scrollView)

        // New recording button（accent 實心 pill）
        addButton = TextView(context).apply {
            text = "＋ 新增錄製"
            setTextColor(Color.parseColor("#0D1117"))
            textSize = 14f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            setPadding(0, (11 * dp).toInt(), 0, (11 * dp).toInt())
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed),
                    OverlayUi.rounded(context, "#3BBE6C", 22f))
                addState(intArrayOf(), OverlayUi.rounded(context, OverlayUi.ACCENT, 22f))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * dp).toInt() }
            setOnClickListener {
                if (tab == 0) {
                    hide()
                    MacroRecorderWindow(context, accessibilityService, scope, prefs).show()
                } else openComboEditor(null)
            }
        }
        rootLayout!!.addView(addButton)

        // 固定寬度：切換分頁或名稱長短時視窗不跳動
        val screenW = context.resources.displayMetrics.widthPixels
        val params = WindowManager.LayoutParams(
            minOf((screenW * 0.92f).toInt(), (400 * dp).toInt()),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        windowManager.addView(rootLayout, params)
        refresh()
    }

    private fun refresh() {
        val container = listContainer ?: return
        container.removeAllViews()
        listOf(tabMacro to 0, tabCombo to 1).forEach { (view, index) ->
            view ?: return@forEach
            val active = tab == index
            view.background = OverlayUi.chipRounded(context, 20f, if (active) OverlayUi.ACCENT_CHIP else OverlayUi.CHIP)
            view.setTextColor(Color.parseColor(if (active) OverlayUi.ACCENT else OverlayUi.TEXT_LO))
            view.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        addButton?.text = if (tab == 0) "＋ 新增錄製" else "＋ 新增組合"
        if (tab == 1) { refreshCombos(container); return }
        val macros = MacroStorage.listAll(context)
        val dp = context.resources.displayMetrics.density

        if (macros.isEmpty()) {
            container.addView(TextView(context).apply {
                text = "尚無巨集，請先錄製"
                setTextColor(Color.parseColor(OverlayUi.TEXT_LO))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, (20 * dp).toInt(), 0, (20 * dp).toInt())
            })
            return
        }

        macros.forEach { macro ->
            // 每個巨集一張卡片：名稱｜重複次數步進器｜播放｜刪除
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = OverlayUi.rounded(context, OverlayUi.CHIP, 14f)
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * dp).toInt() }
            }

            // Name + action count
            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = macro.name
                    setTextColor(Color.parseColor(OverlayUi.TEXT_HI))
                    textSize = 14f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(context).apply {
                    text = "${macro.actions.size} 個動作"
                    setTextColor(Color.parseColor(OverlayUi.TEXT_LO))
                    textSize = 11f
                })
            })

            // 重複次數步進器：1–99 次，1 再減為 ∞（0 = 無限），∞ 再加回 1
            var repeatCount = 1
            val repeatVal = OverlayUi.valueText(context, "×1", widthDp = 38f)
            addRepeatStepper(row, repeatVal) { repeatCount = it(repeatCount); repeatCount }

            // Play button
            row.addView(OverlayUi.iconButton(
                context, "▶", OverlayUi.ACCENT, sizeDp = 36f, textSizeSp = 14f,
                chipColor = OverlayUi.ACCENT_CHIP
            ).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = (6 * dp).toInt()
                setOnClickListener { playMacro(macro, repeatCount) }
            })

            // Edit button
            row.addView(OverlayUi.iconButton(
                context, "✏", OverlayUi.TEXT_HI, sizeDp = 32f, textSizeSp = 13f
            ).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = (4 * dp).toInt()
                setOnClickListener { showEditActionsDialog(macro) }
            })

            // Delete button
            row.addView(OverlayUi.iconButton(
                context, "🗑", OverlayUi.DANGER, sizeDp = 32f, textSizeSp = 13f
            ).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = (4 * dp).toInt()
                setOnClickListener {
                    val used = ComboPlan.usageCount(ComboStorage.listAll(context), macro.id)
                    MacroStorage.delete(context, macro.id)
                    if (used > 0) Toast.makeText(context, "已刪除；有 $used 個組合使用此巨集，請重新編輯", Toast.LENGTH_LONG).show()
                    refresh()
                }
            })

            container.addView(row)
        }
    }

    private fun showEditActionsDialog(macro: Macro) {
        hide()
        editor = MacroEditorWindow(context, scope, macro) { editor = null; show() }.also { it.show() }
    }
    private fun playMacro(macro: Macro, repeat: Int) {
        if (isPlaying) {
            Toast.makeText(context, "播放中，請稍後", Toast.LENGTH_SHORT).show()
            return
        }
        isPlaying = true; stopRequested = false
        hide()
        showStopBar("🔁 ${macro.name}")

        scope.launch {
            try {
                var round = 0
                while (!stopRequested) {
                    round++
                    val roundText = if (repeat == 0) "第${round}輪" else "$round/$repeat"
                    stopBarLabel?.text = "🔁 ${macro.name} $roundText\n準備中..."
                    val ok = runMacroActions(macro) { progress -> "🔁 ${macro.name} $roundText\n$progress" }
                    if (!ok || stopRequested) break
                    if (repeat != 0 && round >= repeat) break
                }
            } finally {
                isPlaying = false
                hideStopBar()
            }
        }
    }

    /** −/數值/＋ 步進器；update 收到步進函式並回傳新值 */
    private fun addRepeatStepper(row: LinearLayout, valueView: TextView, update: ((Int) -> Int) -> Int) {
        row.addView(OverlayUi.iconButton(
            context, "−", OverlayUi.TEXT_HI, sizeDp = 28f, textSizeSp = 14f
        ).apply { setOnClickListener { valueView.text = RepeatCount.label(update(RepeatCount::dec)) } })
        row.addView(valueView)
        row.addView(OverlayUi.iconButton(
            context, "＋", OverlayUi.TEXT_HI, sizeDp = 28f, textSizeSp = 14f
        ).apply { setOnClickListener { valueView.text = RepeatCount.label(update(RepeatCount::inc)) } })
    }

    private fun refreshCombos(container: LinearLayout) {
        val dp = context.resources.displayMetrics.density
        val combos = ComboStorage.listAll(context)
        val macroIds = MacroStorage.listAll(context).map { it.id }.toSet()
        if (combos.isEmpty()) {
            container.addView(TextView(context).apply {
                text = "尚無組合\n可將多個巨集依序串接執行"
                setTextColor(Color.parseColor(OverlayUi.TEXT_LO))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, (20 * dp).toInt(), 0, (20 * dp).toInt())
            })
            return
        }
        combos.forEach { combo ->
            val missing = ComboPlan.missingSteps(combo.steps, macroIds).size
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = OverlayUi.rounded(context, OverlayUi.CHIP, 14f)
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * dp).toInt() }
            }
            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = combo.name
                    setTextColor(Color.parseColor(OverlayUi.TEXT_HI))
                    textSize = 14f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(context).apply {
                    text = if (missing > 0) "⚠ 缺少 $missing 個巨集"
                           else "${combo.steps.size} 步 · 每輪 ${ComboPlan.runsPerRound(combo.steps)} 次"
                    setTextColor(Color.parseColor(if (missing > 0) OverlayUi.DANGER else OverlayUi.TEXT_LO))
                    textSize = 11f
                })
            })

            var repeatCount = 1
            addRepeatStepper(row, OverlayUi.valueText(context, "×1", widthDp = 38f)) {
                repeatCount = it(repeatCount); repeatCount
            }
            row.addView(OverlayUi.iconButton(
                context, "▶", OverlayUi.ACCENT, sizeDp = 36f, textSizeSp = 14f,
                chipColor = OverlayUi.ACCENT_CHIP
            ).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = (6 * dp).toInt()
                alpha = if (missing > 0) .4f else 1f
                setOnClickListener { playCombo(combo, repeatCount) }
            })
            row.addView(OverlayUi.iconButton(
                context, "✏", OverlayUi.TEXT_HI, sizeDp = 32f, textSizeSp = 13f
            ).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = (4 * dp).toInt()
                setOnClickListener { openComboEditor(combo) }
            })
            row.addView(OverlayUi.iconButton(
                context, "🗑", OverlayUi.DANGER, sizeDp = 32f, textSizeSp = 13f
            ).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = (4 * dp).toInt()
                setOnClickListener { ComboStorage.delete(context, combo.id); refresh() }
            })
            container.addView(row)
        }
    }

    private fun openComboEditor(combo: MacroCombo?) {
        hide()
        comboEditor = ComboEditorWindow(context, combo) { comboEditor = null; tab = 1; show() }.also { it.show() }
    }

    /** 依序執行組合中的巨集；任一巨集缺失、失敗或被停止即中止整個組合 */
    private fun playCombo(combo: MacroCombo, repeat: Int) {
        if (isPlaying) {
            Toast.makeText(context, "播放中，請稍後", Toast.LENGTH_SHORT).show()
            return
        }
        val macros = combo.steps.map { MacroStorage.load(context, it.macroId) }
        if (macros.isEmpty() || macros.any { it == null }) {
            Toast.makeText(context, "組合內有巨集已被刪除，請先編輯組合", Toast.LENGTH_SHORT).show()
            return
        }
        isPlaying = true; stopRequested = false
        hide()
        showStopBar("🔗 ${combo.name}")

        scope.launch {
            try {
                var round = 0
                outer@ while (!stopRequested) {
                    round++
                    val roundText = if (repeat == 0) "第${round}輪" else "$round/$repeat"
                    for ((index, step) in combo.steps.withIndex()) {
                        val macro = macros[index]!!
                        for (time in 1..step.repeat) {
                            if (stopRequested) break@outer
                            val stepText = "${index + 1}/${combo.steps.size} ${macro.name}" +
                                if (step.repeat > 1) " ($time/${step.repeat})" else ""
                            val ok = runMacroActions(macro) { progress ->
                                "🔗 $roundText · $stepText\n$progress"
                            }
                            if (!ok) {
                                if (!stopRequested) withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "「${macro.name}」執行失敗，組合已停止", Toast.LENGTH_LONG).show()
                                }
                                break@outer
                            }
                        }
                    }
                    if (repeat != 0 && round >= repeat) break
                }
            } finally {
                isPlaying = false
                hideStopBar()
            }
        }
    }

    /** 執行一輪巨集動作；回傳 false 表示發生錯誤或被停止，應中止循環 */
    private suspend fun runMacroActions(macro: Macro, status: (String) -> String): Boolean {
        val sX = prefs.getInt("startX", 0)
        val sY = prefs.getInt("startY", 1000)
        val oS = prefs.getInt("orbSize", 150)
        val is8Way = prefs.getBoolean("is8Way", false)
        val beamWidth = prefs.getInt("beamWidth", 200)
        val gestureMs = prefs.getLong("gestureMs", 5000L)
        val startMode = prefs.getInt("startMode", 0)

        for ((index, action) in macro.actions.withIndex()) {
            if (stopRequested) return false

            val progressStr = "${index + 1}/${macro.actions.size}"
            val actionDesc = when (action) {
                is MacroAction.Tap -> "點擊"
                is MacroAction.Swipe -> "滑動"
                is MacroAction.Wait -> "等待 ${MacroTime.seconds(action.ms)} 秒"
                is MacroAction.AutoBoard -> "自動盤"
                is MacroAction.UseSkill -> "成員 ${action.memberSlot + 1} 技能 ${action.skillNumber}"
                is MacroAction.FixedBoard -> "固定盤"
            }
            withContext(Dispatchers.Main) {
                stopBarLabel?.text = status("進度: $progressStr $actionDesc")
            }

            when (action) {
                is MacroAction.Tap -> {
                    accessibilityService.performTap(action.x, action.y, action.holdMs)
                }
                is MacroAction.Swipe -> {
                    accessibilityService.performOrbSwipe(action.path, action.durationMs)
                }
                is MacroAction.Wait -> {
                    if (!interruptibleDelay(action.ms)) return false
                }
                is MacroAction.AutoBoard -> {
                    // 等待盤面出現且穩定再解算，取代固定等待
                    val board = waitForStableBoard(sX, sY, oS) ?: return false
                    val (path, _) = withContext(Dispatchers.Default) {
                        BoardAnalyzer().calculateOptimalPath(
                            board = board,
                            orbSize = oS.toFloat(),
                            startX = sX.toFloat(),
                            startY = sY.toFloat(),
                            is8Way = is8Way,
                            strategyMode = action.strategyMode,
                            startMode = startMode,
                            steps = action.steps,
                            beamWidth = beamWidth,
                            shapeGoals = action.shapeGoals
                        )
                    }
                    if (path.isEmpty()) return false
                    // 路徑已是真實螢幕座標，毋需狀態列補償
                    val dur = ((path.size - 1).toLong() * gestureMs / action.steps.toLong())
                        .coerceAtLeast(200L)
                    accessibilityService.performOrbSwipe(path, dur)
                    waitForBoardSettled(sX, sY, oS)
                }
                is MacroAction.UseSkill -> {
                    accessibilityService.performTap(action.tapX, action.tapY, 100L)
                    if (action.hasStage2) {
                        if (!interruptibleDelay(800L)) return false
                        accessibilityService.performTap(action.stage2X, action.stage2Y, 100L)
                    }
                }
                is MacroAction.FixedBoard -> {
                    val current = waitForStableBoard(sX, sY, oS) ?: return false
                    if (boardsMatch(current, action.boardSignature)) {
                        // Recorded path is absolute screen coords — use directly
                        accessibilityService.performOrbSwipe(action.path, action.durationMs)
                    } else {
                        // Fallback: auto-calculate path
                        val (path, _) = withContext(Dispatchers.Default) {
                            BoardAnalyzer().calculateOptimalPath(
                                board = current,
                                orbSize = oS.toFloat(),
                                startX = sX.toFloat(),
                                startY = sY.toFloat(),
                                is8Way = is8Way,
                                strategyMode = 0,
                                startMode = startMode,
                                steps = 35,
                                beamWidth = beamWidth
                            )
                        }
                        if (path.isNotEmpty()) {
                            val dur = ((path.size - 1).toLong() * gestureMs / 35L).coerceAtLeast(200L)
                            accessibilityService.performOrbSwipe(path, dur)
                        }
                    }
                    waitForBoardSettled(sX, sY, oS)
                }
            }
        }
        return !stopRequested
    }

    /** 可中斷等待：stopRequested 時提前結束並回傳 false */
    private suspend fun interruptibleDelay(ms: Long): Boolean {
        var remaining = ms
        while (remaining > 0) {
            if (stopRequested) return false
            val step = minOf(200L, remaining)
            delay(step)
            remaining -= step
        }
        return !stopRequested
    }

    /**
     * 等待盤面出現且穩定：連續兩次辨識結果一致、且未知格 ≤ 6（80% 可辨識）。
     * 逾時或被停止回傳 null。
     */
    private suspend fun waitForStableBoard(
        sX: Int, sY: Int, oS: Int, timeoutMs: Long = 8000L
    ): Array<Array<BoardAnalyzer.OrbType>>? {
        var prev: Array<Array<BoardAnalyzer.OrbType>>? = null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (stopRequested) return null
            val bitmap = ScreenCaptureService.instance?.captureSnapshot()
            if (bitmap == null) { delay(400); continue }
            val board = withContext(Dispatchers.Default) {
                BoardAnalyzer().analyzeBoard(bitmap, sX, sY, oS)
                    .also { if (!bitmap.isRecycled) bitmap.recycle() }
            }
            val unknown = board.sumOf { row -> row.count { it == BoardAnalyzer.OrbType.UNKNOWN } }
            if (unknown <= 6 && prev != null && boardsMatch(board, prev)) return board
            prev = board
            delay(400)
        }
        return null
    }

    /** 轉珠後等待消除/天降動畫結束（盤面重新穩定）；逾時就直接繼續 */
    private suspend fun waitForBoardSettled(sX: Int, sY: Int, oS: Int) {
        if (!interruptibleDelay(600L)) return
        waitForStableBoard(sX, sY, oS, timeoutMs = 8000L)
    }

    private fun showStopBar(title: String) {
        if (stopBar != null) return
        val dp = context.resources.displayMetrics.density
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = OverlayUi.rounded(context, OverlayUi.SURFACE, 22f, stroked = true)
            setPadding((14 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
        }
        stopBarLabel = TextView(context).apply {
            text = "$title\n準備中..."
            setTextColor(Color.parseColor(OverlayUi.TEXT_HI)); textSize = 13f
            setPadding(0, 0, (10 * dp).toInt(), 0)
            minLines = 2
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = (180 * dp).toInt()
        }
        bar.addView(stopBarLabel)
        bar.addView(OverlayUi.pillButton(
            context, "■ 停止", textColor = OverlayUi.DANGER
        ).apply {
            maxLines = 1
            setOnClickListener { stopRequested = true }
        })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (8 * dp).toInt(); y = (80 * dp).toInt()
        }
        windowManager.addView(bar, params)
        stopBar = bar
    }

    private fun hideStopBar() {
        stopBar?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        stopBar = null; stopBarLabel = null
    }

    private fun boardsMatch(
        a: Array<Array<BoardAnalyzer.OrbType>>,
        b: Array<Array<BoardAnalyzer.OrbType>>
    ): Boolean = a.size == b.size &&
        a.indices.all { r -> a[r].size == b[r].size && a[r].indices.all { c -> a[r][c] == b[r][c] } }

    fun hide() {
        editor?.dismiss()
        editor = null
        comboEditor?.dismiss()
        comboEditor = null
        rootLayout?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        rootLayout = null
        listContainer = null
    }
}
