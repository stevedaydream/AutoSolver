package com.stevedaydream.autodora

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * 腳本錄製視窗 — 真實互動錄製模式
 *
 * 流程：
 *  1. 設定隊伍人數
 *  2. 半透明按鈕框覆蓋在成員圖示上，逐一配置技能（含第二段）
 *     → 每次確認技能後，立即重放對應點擊給遊戲（真實觸發）
 *  3. 「完成配置 → 開始錄製」切換成遮罩模式
 *     → 每個觸控：遮罩攔截記錄 → 切為穿透 → AccessibilityService 重放給遊戲 → 恢復攔截
 */
class MacroRecorderWindow(
    private val context: Context,
    private val accessibilityService: AutoPuzzlerAccessibilityService,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // ── 成員設定階段 ─────────────────────────────────────────────────────────
    private var memberCount = 6
    private var memberOverlayView: FrameLayout? = null
    private var memberOverlayParams: WindowManager.LayoutParams? = null
    private val memberBtns = mutableListOf<View>()
    private var stage2CaptureView: View? = null
    private var stage2HintBar: TextView? = null
    private var memberActionCountText: TextView? = null
    private var awaitingStage2 = false
    private data class PendingStage2(val slot: Int, val skillNum: Int, val tapX: Float, val tapY: Float)
    private var pendingStage2: PendingStage2? = null
    private val activeDialogs = mutableListOf<View>()

    // ── 錄製階段 ─────────────────────────────────────────────────────────────
    private var rootLayout: FrameLayout? = null
    private var recordingParams: WindowManager.LayoutParams? = null
    private lateinit var controlBar: LinearLayout
    private lateinit var statusText: TextView
    private var controlBarHeight = 0
    private val actions = mutableListOf<MacroAction>()
    private var lastActionTime = 0L
    private var isPaused = false
    private var pendingBoardSignature: Array<Array<BoardAnalyzer.OrbType>>? = null
    private val touchPath = mutableListOf<Pair<Float, Float>>()
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L

    // ─────────────────────────────────────────────────────────────────────────

    fun show() = showSetupDialog()

    // ── 步驟 1：隊伍人數對話框 ────────────────────────────────────────────────
    private fun showSetupDialog() {
        val dp = context.resources.displayMetrics.density
        var count = memberCount
        val countText = TextView(context).apply {
            text = "$count"; setTextColor(Color.YELLOW)
            textSize = 28f; gravity = Gravity.CENTER
            minWidth = (56 * dp).toInt()
        }
        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.parseColor("#F02C3E50")); cornerRadius = 20f }
            setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt())
            gravity = Gravity.CENTER
        }
        dialogView.addView(TextView(context).apply {
            text = "⚙ 隊伍成員數量"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, (12 * dp).toInt())
        })
        dialogView.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(makeBtn("－", "#E74C3C") { if (count > 1) { count--; countText.text = "$count" } })
            addView(countText)
            addView(makeBtn("＋", "#2ECC71") { if (count < 6) { count++; countText.text = "$count" } })
        })
        dialogView.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            setPadding(0, (16 * dp).toInt(), 0, 0)
            addView(makeBtn("取消", "#95A5A6") { dismissDialog(dialogView); hide() })
            addView(makeBtn("開始配置 →", "#2980B9") {
                memberCount = count; dismissDialog(dialogView); showMemberOverlay()
            })
        })
        val params = WindowManager.LayoutParams(
            (260 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 0, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        windowManager.addView(dialogView, params)
        activeDialogs.add(dialogView)
    }

    // ── 步驟 2：成員按鈕覆蓋層 ───────────────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    private fun showMemberOverlay() {
        val dp = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        val sbH = getStatusBarHeight()
        val startY = prefs.getInt("startY", 1000)
        val orbSize = prefs.getInt("orbSize", 150)
        val btnH = orbSize.toFloat().coerceIn(80f * dp, 160f * dp).toInt()
        val btnW = screenWidth / memberCount
        val memberAreaCenterY = ((startY - sbH) / 2).coerceAtLeast(0)

        val overlayRoot = FrameLayout(context)
        memberOverlayView = overlayRoot
        memberBtns.clear(); actions.clear()

        // ── 第二段目標捕捉覆蓋（z-order 最高，初始不可見）────────────────────
        val s2Capture = object : View(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_UP) {
                    val p = pendingStage2 ?: return true
                    val sx = event.rawX; val sy = event.rawY
                    actions.add(MacroAction.UseSkill(p.slot, p.skillNum, true, p.tapX, p.tapY, sx, sy))
                    awaitingStage2 = false; pendingStage2 = null
                    this.visibility = View.INVISIBLE
                    stage2HintBar?.visibility = View.GONE
                    memberActionCountText?.text = "已配置 ${actions.size} 個技能動作"
                    // 重放第二段點擊給遊戲
                    dispatchTapNow(sx, sy, 100L, memberOverlayView, memberOverlayParams) {
                        memberBtns.forEach { it.visibility = View.VISIBLE }
                        Toast.makeText(context, "✓ 第二段目標已記錄並執行", Toast.LENGTH_SHORT).show()
                    }
                }
                return true
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#44000000"))
            visibility = View.INVISIBLE
        }
        stage2CaptureView = s2Capture

        // ── 第二段提示列 ─────────────────────────────────────────────────────
        val hintBar = TextView(context).apply {
            text = "👆 請點選技能第二段目標位置（將直接傳給遊戲）"
            setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(0, (16 * dp).toInt(), 0, (16 * dp).toInt())
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = Gravity.TOP
                topMargin = (memberAreaCenterY - btnH).coerceAtLeast(0)
            }
        }
        stage2HintBar = hintBar

        // ── 成員按鈕 ─────────────────────────────────────────────────────────
        for (slot in 0 until memberCount) {
            val tapX = slot * btnW + btnW / 2f
            val tapY = sbH + memberAreaCenterY.toFloat()
            val btn = Button(context).apply {
                text = "成員\n${slot + 1}"; setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#882980B9")); cornerRadius = 12f
                    setStroke((2 * dp).toInt(), Color.parseColor("#FF5DADE2"))
                }
                layoutParams = FrameLayout.LayoutParams(btnW - (8 * dp).toInt(), btnH).apply {
                    leftMargin = slot * btnW + (4 * dp).toInt()
                    topMargin = memberAreaCenterY - btnH / 2
                }
                setOnClickListener { showSkillDialog(slot, tapX, tapY) }
            }
            memberBtns.add(btn); overlayRoot.addView(btn)
        }

        // ── 底部控制列 ───────────────────────────────────────────────────────
        val actionCount = TextView(context).apply {
            text = "已配置 0 個技能動作"
            setTextColor(Color.parseColor("#AAAAAA")); textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        }
        memberActionCountText = actionCount

        val bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#DD1A252F"))
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
        }
        bottomBar.addView(makeBtn("✖ 取消", "#E74C3C") { hide() })
        bottomBar.addView(actionCount)
        bottomBar.addView(makeBtn("✅ 完成 → 錄製", "#2ECC71") { startFreeRecord() })

        overlayRoot.addView(hintBar)
        overlayRoot.addView(bottomBar)
        overlayRoot.addView(s2Capture)  // 最高 z-order

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        memberOverlayParams = params
        windowManager.addView(overlayRoot, params)
    }

    // ── 技能選擇對話框 ────────────────────────────────────────────────────────
    private fun showSkillDialog(slot: Int, tapX: Float, tapY: Float) {
        val dp = context.resources.displayMetrics.density
        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.parseColor("#F02C3E50")); cornerRadius = 20f }
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
        }
        dialogView.addView(TextView(context).apply {
            text = "成員 ${slot + 1}"; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, (10 * dp).toInt())
        })
        dialogView.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(makeBtn("技能 1", "#3498DB") { dismissDialog(dialogView); showSkillStageDialog(slot, 1, tapX, tapY) })
            addView(makeBtn("技能 2", "#9B59B6") { dismissDialog(dialogView); showSkillStageDialog(slot, 2, tapX, tapY) })
            addView(makeBtn("取消", "#95A5A6") { dismissDialog(dialogView) })
        })
        val params = WindowManager.LayoutParams(
            (260 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 0, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        windowManager.addView(dialogView, params)
        activeDialogs.add(dialogView)
    }

    // ── 技能第二段對話框 ──────────────────────────────────────────────────────
    private fun showSkillStageDialog(slot: Int, skillNum: Int, tapX: Float, tapY: Float) {
        val dp = context.resources.displayMetrics.density
        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.parseColor("#F02C3E50")); cornerRadius = 20f }
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
        }
        dialogView.addView(TextView(context).apply {
            text = "成員 ${slot + 1}  技能 $skillNum"
            setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
        })
        dialogView.addView(TextView(context).apply {
            text = "此技能有第二段嗎？\n（如需點選場地目標才生效）"
            setTextColor(Color.parseColor("#BBBBBB")); textSize = 12f; gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        })
        dialogView.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(makeBtn("有第二段 →", "#E67E22") {
                dismissDialog(dialogView)
                // 先重放技能點擊給遊戲，等遊戲顯示第二段 UI 後再進入捕捉模式
                dispatchTapNow(tapX, tapY, 100L, memberOverlayView, memberOverlayParams) {
                    pendingStage2 = PendingStage2(slot, skillNum, tapX, tapY)
                    awaitingStage2 = true
                    memberBtns.forEach { it.visibility = View.INVISIBLE }
                    stage2CaptureView?.visibility = View.VISIBLE
                    stage2HintBar?.visibility = View.VISIBLE
                }
            })
            addView(makeBtn("無第二段", "#2ECC71") {
                dismissDialog(dialogView)
                actions.add(MacroAction.UseSkill(slot, skillNum, false, tapX, tapY))
                memberActionCountText?.text = "已配置 ${actions.size} 個技能動作"
                // 重放技能點擊給遊戲
                dispatchTapNow(tapX, tapY, 100L, memberOverlayView, memberOverlayParams) {
                    Toast.makeText(context, "✓ 成員${slot + 1} 技能$skillNum 已加入並執行", Toast.LENGTH_SHORT).show()
                }
            })
            addView(makeBtn("取消", "#95A5A6") { dismissDialog(dialogView) })
        })
        val params = WindowManager.LayoutParams(
            (280 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 0, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        windowManager.addView(dialogView, params)
        activeDialogs.add(dialogView)
    }

    // ── 步驟 3：切換到真實互動遮罩錄製 ──────────────────────────────────────
    private fun startFreeRecord() {
        memberOverlayView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        memberOverlayView = null; memberOverlayParams = null
        lastActionTime = System.currentTimeMillis()

        val root = FrameLayout(context)
        rootLayout = root
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        recordingParams = params

        root.addView(buildOverlayView())
        buildControlBar()
        root.addView(controlBar)
        windowManager.addView(root, params)
        updateStatusText()
    }

    // ── 真實互動觸控覆蓋層 ────────────────────────────────────────────────────
    // 攔截觸控 → 記錄 → 切為穿透 → AccessibilityService 重放給遊戲 → 恢復攔截
    @SuppressLint("ClickableViewAccessibility")
    private fun buildOverlayView(): View = object : View(context) {
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (controlBarHeight > 0 && event.y < controlBarHeight) return false
            if (isPaused) return true

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val now = System.currentTimeMillis()
                    if (lastActionTime > 0) {
                        val gap = now - lastActionTime
                        if (gap > 300L) actions.add(MacroAction.Wait(gap))
                    }
                    touchDownX = event.rawX; touchDownY = event.rawY
                    touchDownTime = now
                    touchPath.clear()
                    touchPath.add(Pair(event.rawX, event.rawY))
                }
                MotionEvent.ACTION_MOVE -> touchPath.add(Pair(event.rawX, event.rawY))
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchDownTime
                    val dx = event.rawX - touchDownX; val dy = event.rawY - touchDownY
                    val dist = hypot(dx.toDouble(), dy.toDouble())
                    val dp20 = 20 * context.resources.displayMetrics.density

                    val action: MacroAction = if (dist < dp20) {
                        MacroAction.Tap(touchDownX, touchDownY)
                    } else {
                        val pending = pendingBoardSignature
                        if (pending != null) {
                            pendingBoardSignature = null
                            MacroAction.FixedBoard(pending, touchPath.toList(), elapsed.coerceAtLeast(200L))
                        } else {
                            MacroAction.Swipe(touchPath.toList(), elapsed.coerceAtLeast(200L))
                        }
                    }
                    actions.add(action)
                    updateStatusText()

                    // 切穿透 → 重放給遊戲 → 恢復攔截，lastActionTime 在重放完成後才設定
                    val rl = rootLayout ?: return true
                    val rp = recordingParams ?: return true
                    when (action) {
                        is MacroAction.Tap ->
                            dispatchTapNow(action.x, action.y, action.holdMs, rl, rp) {
                                lastActionTime = System.currentTimeMillis()
                            }
                        is MacroAction.Swipe ->
                            dispatchSwipeNow(action.path, action.durationMs, rl, rp) {
                                lastActionTime = System.currentTimeMillis()
                            }
                        is MacroAction.FixedBoard ->
                            dispatchSwipeNow(action.path, action.durationMs, rl, rp) {
                                lastActionTime = System.currentTimeMillis()
                            }
                        else -> lastActionTime = System.currentTimeMillis()
                    }
                }
            }
            return true
        }
    }.apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.TRANSPARENT)
    }

    // ── 重放核心：切穿透 → 手勢 → 恢復攔截 ────────────────────────────────────
    private fun dispatchTapNow(
        x: Float, y: Float, holdMs: Long,
        windowView: View?, params: WindowManager.LayoutParams?,
        onComplete: (() -> Unit)? = null
    ) {
        if (windowView == null || params == null || !windowView.isAttachedToWindow) {
            onComplete?.invoke(); return
        }
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        windowManager.updateViewLayout(windowView, params)
        scope.launch {
            accessibilityService.performTap(x, y, holdMs)
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            windowManager.updateViewLayout(windowView, params)
            onComplete?.invoke()
        }
    }

    private fun dispatchSwipeNow(
        path: List<Pair<Float, Float>>, durationMs: Long,
        windowView: View?, params: WindowManager.LayoutParams?,
        onComplete: (() -> Unit)? = null
    ) {
        if (windowView == null || params == null || !windowView.isAttachedToWindow) {
            onComplete?.invoke(); return
        }
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        windowManager.updateViewLayout(windowView, params)
        scope.launch {
            accessibilityService.performOrbSwipe(path, durationMs)
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            windowManager.updateViewLayout(windowView, params)
            onComplete?.invoke()
        }
    }

    // ── 控制列 ────────────────────────────────────────────────────────────────
    private fun buildControlBar() {
        val dp = context.resources.displayMetrics.density
        controlBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#EE1A252F"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP }
        }
        var pauseBtn: TextView? = null
        val btnFixed = makeBtn("📷固定盤", "#E67E22") { captureSignature() }
        val btnAuto  = makeBtn("🎯自動盤", "#3498DB") { addAutoBoard() }
        pauseBtn = makeBtn("⏸", "#F1C40F") {
            isPaused = !isPaused; pauseBtn?.text = if (isPaused) "▶" else "⏸"; updateStatusText()
        }
        val btnUndo = makeBtn("🗑", "#95A5A6") {
            if (actions.isNotEmpty()) { actions.removeAt(actions.lastIndex); updateStatusText() }
        }
        val btnDone = makeBtn("⏹完成", "#2ECC71") { finishRecording() }
        statusText = TextView(context).apply {
            setTextColor(Color.parseColor("#BBBBBB")); textSize = 11f
            setPadding((8 * dp).toInt(), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        controlBar.apply {
            addView(btnFixed); addView(btnAuto); addView(pauseBtn)
            addView(btnUndo); addView(btnDone); addView(statusText)
        }
        controlBar.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                controlBarHeight = controlBar.height
                controlBar.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────
    private fun captureSignature() {
        val bitmap = ScreenCaptureService.instance?.captureSnapshot() ?: run {
            Toast.makeText(context, "擷取畫面失敗", Toast.LENGTH_SHORT).show(); return
        }
        val sX = prefs.getInt("startX", 0)
        val sY = prefs.getInt("startY", 1000)
        val oS = prefs.getInt("orbSize", 150)
        scope.launch {
            val board = withContext(Dispatchers.Default) {
                BoardAnalyzer().analyzeBoard(bitmap, sX, sY, oS)
                    .also { if (!bitmap.isRecycled) bitmap.recycle() }
            }
            pendingBoardSignature = board
            updateStatusText()
            Toast.makeText(context, "已記錄盤面，下一個滑動將綁定此盤", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addAutoBoard() {
        val now = System.currentTimeMillis()
        if (lastActionTime > 0 && now - lastActionTime > 300L)
            actions.add(MacroAction.Wait(now - lastActionTime))
        // 記錄當前設定面板的步數與策略（ML 模式巨集不支援，降為一般）
        val steps = prefs.getInt("steps", 35)
        val strat = prefs.getInt("strategyMode", 0).let { if (it == 3) 0 else it }
        actions.add(MacroAction.AutoBoard(steps, strat))
        lastActionTime = System.currentTimeMillis()
        updateStatusText()
        Toast.makeText(context, "已加入：自動辨識盤面", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatusText() {
        val lastStr = actions.lastOrNull()?.let {
            val label = when (it) {
                is MacroAction.Tap -> "點擊"
                is MacroAction.Swipe -> "滑動"
                is MacroAction.Wait -> "等待${(it.ms + 500) / 1000}s"
                is MacroAction.AutoBoard -> "自動盤"
                is MacroAction.UseSkill -> "技能${it.skillNumber}"
                is MacroAction.FixedBoard -> "固定盤"
            }
            " · 最後:$label"
        } ?: ""
        val pendStr  = if (pendingBoardSignature != null) " ●固定待綁" else ""
        val pauseStr = if (isPaused) " [暫停]" else ""
        statusText.text = "⏺ ${actions.size}個動作$lastStr$pendStr$pauseStr"
    }

    private fun finishRecording() {
        if (actions.isEmpty()) {
            Toast.makeText(context, "尚未錄製任何動作", Toast.LENGTH_SHORT).show(); return
        }
        showNameDialog { name ->
            val macro = Macro(
                id = MacroStorage.newId(), name = name,
                actions = actions.toList(), createdAt = System.currentTimeMillis()
            )
            MacroStorage.save(context, macro)
            Toast.makeText(context, "已儲存：${macro.name}（${macro.actions.size}個動作）", Toast.LENGTH_SHORT).show()
            hide()
        }
    }

    private fun showNameDialog(onConfirm: (String) -> Unit) {
        val dp = context.resources.displayMetrics.density
        val defaultName = "Macro_${System.currentTimeMillis() % 100000}"
        val editText = EditText(context).apply {
            setText(defaultName); selectAll(); setTextColor(Color.WHITE)
            hint = "Macro 名稱"; setHintTextColor(Color.parseColor("#888888"))
            setBackgroundColor(Color.parseColor("#44FFFFFF"))
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        }
        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.parseColor("#F02C3E50")); cornerRadius = 20f }
            setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
        }
        dialogView.addView(TextView(context).apply {
            text = "儲存 Macro"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, (12 * dp).toInt())
        })
        dialogView.addView(editText)
        dialogView.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            setPadding(0, (12 * dp).toInt(), 0, 0)
            addView(Button(context).apply {
                text = "取消"; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { if (dialogView.isAttachedToWindow) windowManager.removeView(dialogView) }
            })
            addView(Button(context).apply {
                text = "儲存"
                setOnClickListener {
                    val name = editText.text.toString().trim().ifBlank { defaultName }
                    if (dialogView.isAttachedToWindow) windowManager.removeView(dialogView)
                    onConfirm(name)
                }
            })
        })
        val params = WindowManager.LayoutParams(
            (280 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER; dimAmount = 0.6f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        windowManager.addView(dialogView, params)
        editText.post { editText.requestFocus() }
    }

    private fun makeBtn(label: String, color: String, onClick: () -> Unit) = TextView(context).apply {
        text = label; setTextColor(Color.parseColor(color)); setBackgroundColor(Color.TRANSPARENT)
        textSize = 13f; setPadding(14, 10, 14, 10)
        setOnClickListener { onClick() }
    }

    private fun dismissDialog(view: View) {
        if (view.isAttachedToWindow) windowManager.removeView(view)
        activeDialogs.remove(view)
    }

    private fun getStatusBarHeight(): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
    }

    fun hide() {
        activeDialogs.toList().forEach { if (it.isAttachedToWindow) windowManager.removeView(it) }
        activeDialogs.clear()
        memberOverlayView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        memberOverlayView = null; memberOverlayParams = null
        rootLayout?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        rootLayout = null; recordingParams = null
    }
}
