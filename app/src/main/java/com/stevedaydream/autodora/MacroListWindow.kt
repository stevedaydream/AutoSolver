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
        rootLayout!!.addView(TextView(context).apply {
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
                hide()
                MacroRecorderWindow(context, accessibilityService, scope, prefs).show()
            }
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
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
            val updateRepeatLabel = {
                repeatVal.text = if (repeatCount == 0) "∞" else "×$repeatCount"
            }
            row.addView(OverlayUi.iconButton(
                context, "−", OverlayUi.TEXT_HI, sizeDp = 28f, textSizeSp = 14f
            ).apply { setOnClickListener {
                repeatCount = when {
                    repeatCount == 0 -> 99
                    repeatCount <= 1 -> 0
                    else -> repeatCount - 1
                }
                updateRepeatLabel()
            } })
            row.addView(repeatVal)
            row.addView(OverlayUi.iconButton(
                context, "＋", OverlayUi.TEXT_HI, sizeDp = 28f, textSizeSp = 14f
            ).apply { setOnClickListener {
                repeatCount = when {
                    repeatCount == 0 -> 1
                    repeatCount >= 99 -> 0
                    else -> repeatCount + 1
                }
                updateRepeatLabel()
            } })

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
                    MacroStorage.delete(context, macro.id)
                    refresh()
                }
            })

            container.addView(row)
        }
    }

    private fun showEditActionsDialog(macro: Macro) {
        val dp = context.resources.displayMetrics.density
        val editedActions = macro.actions.toMutableList()

        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = OverlayUi.rounded(context, OverlayUi.SURFACE_CARD, 20f, stroked = true)
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }

        // Title text
        val titleText = TextView(context).apply {
            text = "編輯動作: ${macro.name}"
            setTextColor(Color.parseColor(OverlayUi.TEXT_HI))
            textSize = 15f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setPadding(0, 0, 0, (10 * dp).toInt())
        }
        dialogView.addView(titleText)

        // ScrollView for action items
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (240 * dp).toInt()
            )
        }
        val itemsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(itemsContainer)
        dialogView.addView(scrollView)

        // Function to refresh the list of actions in the editor dialog
        fun refreshEditorList() {
            itemsContainer.removeAllViews()
            if (editedActions.isEmpty()) {
                itemsContainer.addView(TextView(context).apply {
                    text = "無動作"
                    setTextColor(Color.parseColor(OverlayUi.TEXT_LO))
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(0, (20 * dp).toInt(), 0, (20 * dp).toInt())
                })
                return
            }

            editedActions.forEachIndexed { index, action ->
                val itemRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((8 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                    background = OverlayUi.rounded(context, OverlayUi.CHIP, 8f)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (4 * dp).toInt() }
                }

                // Action description text
                val actionText = TextView(context).apply {
                    val actionDesc = when (action) {
                        is MacroAction.Tap -> "點擊 (${action.x.toInt()}, ${action.y.toInt()})"
                        is MacroAction.Swipe -> "滑動 (長度: ${action.path.size})"
                        is MacroAction.Wait -> "等待 ${action.ms}ms"
                        is MacroAction.AutoBoard -> "自動解盤 (步數: ${action.steps})"
                        is MacroAction.UseSkill -> "成員 ${action.memberSlot + 1} 技能 ${action.skillNumber}"
                        is MacroAction.FixedBoard -> "固定盤面"
                    }
                    text = "${index + 1}. $actionDesc"
                    setTextColor(Color.parseColor(OverlayUi.TEXT_HI))
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                itemRow.addView(actionText)

                // Delete button for this specific action
                val delActBtn = OverlayUi.iconButton(context, "🗑", OverlayUi.DANGER, sizeDp = 28f, textSizeSp = 12f)
                delActBtn.setOnClickListener {
                    editedActions.removeAt(index)
                    refreshEditorList()
                }
                itemRow.addView(delActBtn)

                itemsContainer.addView(itemRow)
            }
        }

        refreshEditorList()

        // Bottom action buttons: Save & Cancel
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (12 * dp).toInt(), 0, 0)
        }

        // Cancel button
        val btnCancel = OverlayUi.pillButton(context, "取消", textColor = OverlayUi.TEXT_LO).apply {
            setOnClickListener {
                if (dialogView.isAttachedToWindow) {
                    windowManager.removeView(dialogView)
                }
            }
        }

        // Save button
        val btnSave = OverlayUi.pillButton(context, "儲存", textColor = OverlayUi.ACCENT, chipColor = OverlayUi.ACCENT_CHIP).apply {
            setOnClickListener {
                val updatedMacro = Macro(
                    id = macro.id,
                    name = macro.name,
                    actions = editedActions.toList(),
                    createdAt = macro.createdAt
                )
                MacroStorage.save(context, updatedMacro)
                Toast.makeText(context, "巨集動作已更新", Toast.LENGTH_SHORT).show()
                if (dialogView.isAttachedToWindow) {
                    windowManager.removeView(dialogView)
                }
                refresh() // Refresh the macro list view
            }
        }

        buttonRow.addView(btnCancel)
        val gap = View(context).apply {
            layoutParams = LinearLayout.LayoutParams((8 * dp).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        buttonRow.addView(gap)
        buttonRow.addView(btnSave)
        dialogView.addView(buttonRow)

        val params = WindowManager.LayoutParams(
            (280 * dp).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.6f
        }

        windowManager.addView(dialogView, params)
    }

    private fun playMacro(macro: Macro, repeat: Int) {
        if (isPlaying) {
            Toast.makeText(context, "播放中，請稍後", Toast.LENGTH_SHORT).show()
            return
        }
        isPlaying = true; stopRequested = false
        hide()
        showStopBar(macro.name)

        scope.launch {
            try {
                var round = 0
                while (!stopRequested) {
                    round++
                    val roundText = if (repeat == 0) "第${round}輪" else "$round/$repeat"
                    stopBarLabel?.text = "🔁 ${macro.name} $roundText\n準備中..."
                    val ok = runMacroActions(macro, round, repeat)
                    if (!ok || stopRequested) break
                    if (repeat != 0 && round >= repeat) break
                }
            } finally {
                isPlaying = false
                hideStopBar()
            }
        }
    }

    /** 執行一輪巨集動作；回傳 false 表示發生錯誤或被停止，應中止循環 */
    private suspend fun runMacroActions(macro: Macro, round: Int, repeat: Int): Boolean {
        val sX = prefs.getInt("startX", 0)
        val sY = prefs.getInt("startY", 1000)
        val oS = prefs.getInt("orbSize", 150)
        val is8Way = prefs.getBoolean("is8Way", false)
        val beamWidth = prefs.getInt("beamWidth", 200)
        val gestureMs = prefs.getLong("gestureMs", 5000L)
        val startMode = prefs.getInt("startMode", 0)

        for ((index, action) in macro.actions.withIndex()) {
            if (stopRequested) return false

            val roundStr = if (repeat == 0) "第${round}輪" else "$round/$repeat"
            val progressStr = "${index + 1}/${macro.actions.size}"
            val actionDesc = when (action) {
                is MacroAction.Tap -> "點擊"
                is MacroAction.Swipe -> "滑動"
                is MacroAction.Wait -> "等待 ${action.ms}ms"
                is MacroAction.AutoBoard -> "自動盤"
                is MacroAction.UseSkill -> "成員 ${action.memberSlot + 1} 技能 ${action.skillNumber}"
                is MacroAction.FixedBoard -> "固定盤"
            }
            withContext(Dispatchers.Main) {
                stopBarLabel?.text = "🔁 ${macro.name} $roundStr\n進度: $progressStr $actionDesc"
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
                            beamWidth = beamWidth
                        )
                    }
                    if (path.isNotEmpty()) {
                        // 路徑已是真實螢幕座標，毋需狀態列補償
                        val dur = ((path.size - 1).toLong() * gestureMs / action.steps.toLong())
                            .coerceAtLeast(200L)
                        accessibilityService.performOrbSwipe(path, dur)
                        waitForBoardSettled(sX, sY, oS)
                    }
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

    private fun showStopBar(name: String) {
        if (stopBar != null) return
        val dp = context.resources.displayMetrics.density
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = OverlayUi.rounded(context, OverlayUi.SURFACE, 22f, stroked = true)
            setPadding((14 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
        }
        stopBarLabel = TextView(context).apply {
            text = "🔁 $name\n準備中..."
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
        rootLayout?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        rootLayout = null
        listContainer = null
    }
}
