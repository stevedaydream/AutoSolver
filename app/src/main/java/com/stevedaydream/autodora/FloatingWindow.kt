package com.stevedaydream.autodora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import kotlinx.coroutines.*
import kotlin.math.abs

class FloatingWindow(private val context: Context, private val accessibilityService: AutoPuzzlerAccessibilityService) {

    enum class Phase(val label: String, val color: String) {
        IDLE     ("待命",          "#95A5A6"),
        CAPTURE  ("擷取畫面中…",   "#3498DB"),
        ANALYZE  ("辨識盤面中…",   "#E67E22"),
        COMPUTE  ("計算路徑中…",   "#9B59B6"),
        GESTURE  ("執行手勢中…",   "#2ECC71"),
        SUCCESS  ("完成",          "#27AE60"),
        FALLBACK ("降級策略",      "#F39C12"),
        ERROR    ("發生錯誤",      "#E74C3C"),
        STOPPED  ("已停止",        "#7F8C8D"),
        NO_PATH  ("找不到路徑",    "#E74C3C"),
        PREVIEW  ("辨識完成",      "#1ABC9C")
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootLayout: LinearLayout? = null
    private lateinit var params: WindowManager.LayoutParams

    private lateinit var topRow: LinearLayout
    private lateinit var toggleBtn: TextView
    private lateinit var menuScrollView: HorizontalScrollView
    private lateinit var menuLayout: LinearLayout
    private lateinit var statusCard: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private lateinit var statusDetail: TextView
    private lateinit var settingsPanel: LinearLayout
    private lateinit var autoBar: LinearLayout  // 自動執行時的精簡列（■ 停止）

    private var isExpanded = false
    private var isSettingsOpen = false
    private val prefs = context.getSharedPreferences("AutoDoraPrefs", Context.MODE_PRIVATE)

    // 解算/執行設定：全部持久化，重開 App 保留，巨集播放也讀同一組值
    private var currentSteps = prefs.getInt("steps", 35)
    private var strategyMode = prefs.getInt("strategyMode", 0) // 0: 一般, 1: 平鋪, 2: 直排昇華, 3: ML
    private var startMode = prefs.getInt("startMode", 0)       // 0: 全盤, 1: 左下, 2: 右下
    private var currentGestureMs = prefs.getLong("gestureMs", 5000L)             // 轉珠總時間（毫秒）
    private var currentRoundIntervalMs = prefs.getLong("roundIntervalMs", 4500L) // 自動輪間隔（毫秒）

    private var lastCalculatedResult: Pair<List<Pair<Float, Float>>, Int>? = null
    @Volatile private var isAutoRunning = false
    @Volatile private var isGestureRunning = false
    @Volatile private var autoRound = 0
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var previewWindow: BoardPreviewWindow? = null
    private var macroListWindow: MacroListWindow? = null
    private val pathOverlayWindow = PathOverlayWindow(context)
    private val recognitionOverlayWindow = RecognitionOverlayWindow(context)
    private var mlPlanner: MLOrbPlanner? = null
    private var selectedMLModel: String = prefs.getString("selectedMLModel", "orb_policy_general.tflite") ?: "orb_policy_general.tflite"
    private var is8Way = prefs.getBoolean("is8Way", false)          // 斜向移動（8 方向）
    private var beamWidth = prefs.getInt("beamWidth", 200)          // beam search 搜尋廣度

    fun show() {
        if (rootLayout != null) return
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 300 }

        // 初始化辨識資料庫（A/B 類樣本）
        if (RecognitionDatabase.instance == null) {
            RecognitionDatabase.instance = RecognitionDatabase(context)
        }

        rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.START
        }

        setupTopRow()
        setupResultRow()
        setupSettingsPanel()
        rootLayout?.addView(topRow); rootLayout?.addView(statusCard); rootLayout?.addView(settingsPanel)
        setupDragAndSnapListener()
        windowManager.addView(rootLayout, params)
    }

    private fun setupTopRow() {
        val mkBg = { GradientDrawable().apply { setColor(Color.parseColor("#CC2C3E50")); cornerRadius = 24f } }
        topRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        toggleBtn = TextView(context).apply { text = "🤖"; textSize = 28f; setPadding(24, 24, 24, 24); background = mkBg() }
        menuScrollView = HorizontalScrollView(context).apply { visibility = View.GONE; isHorizontalScrollBarEnabled = false }
        menuLayout = LinearLayout(context).apply { background = mkBg(); orientation = LinearLayout.HORIZONTAL }

        // ▶ 一鍵模式：辨識 + 立即執行（原拆分的 🔍/▶ 已移至設定面板）
        val btnPlay = createMenuButton("▶", "#2ECC71")
        val btnPreview = createMenuButton("👁", "#E67E22")
        val btnAuto = createMenuButton("🔁", "#9B59B6")
        val btnStop = createMenuButton("■", "#E74C3C")
        val btnMacro = createMenuButton("⏺", "#EC407A")
        val btnSettings = createMenuButton("⚙", "#F1C40F")
        val btnClose = createMenuButton("✖", "#95A5A6")

        btnPlay.setOnClickListener {
            if (isGestureRunning) return@setOnClickListener
            analyzeAndComputePath(autoExecute = true)
        }

        btnPreview.setOnClickListener {
            val bitmap = ScreenCaptureService.instance?.captureSnapshot() ?: return@setOnClickListener
            val sX = prefs.getInt("startX", 0)
            val sY = prefs.getInt("startY", 1000)
            val oS = prefs.getInt("orbSize", 150)
            tryLoadTemplates()
            updateStatus(Phase.ANALYZE)
            scope.launch {
                try {
                    val (board, features) = withContext(Dispatchers.Default) {
                        val analyzer = BoardAnalyzer()
                        val result = analyzer.analyzeBoard(bitmap, sX, sY, oS)
                        if (!bitmap.isRecycled) bitmap.recycle()
                        Pair(result, analyzer.lastFeatures)
                    }
                    pathOverlayWindow.hide()
                    recognitionOverlayWindow.show(board, features, sX, sY, oS)
                    updateStatus(Phase.PREVIEW, note = "點擊盤面關閉")
                } catch (e: Throwable) {
                    updateStatus(Phase.ERROR, note = e.javaClass.simpleName)
                }
            }
        }

        btnAuto.setOnClickListener {
            if (isAutoRunning) return@setOnClickListener

            // ── 進入 auto 模式：收折選單，只留 autoBar + statusCard ──
            isAutoRunning = true; autoRound = 0; pathOverlayWindow.hide()
            isExpanded = false; isSettingsOpen = false
            menuScrollView.visibility = View.GONE
            settingsPanel.visibility  = View.GONE
            autoBar.visibility        = View.VISIBLE
            statusCard.visibility     = View.VISIBLE
            refreshWindowLayout()
            tryLoadTemplates()

            scope.launch {
                while (isAutoRunning) {
                    autoRound++
                    val sX = prefs.getInt("startX", 0)
                    val sY = prefs.getInt("startY", 1000)
                    val oS = prefs.getInt("orbSize", 150)
                    updateStatus(Phase.CAPTURE, round = autoRound)
                    val bitmap = ScreenCaptureService.instance?.captureSnapshot() ?: break
                    updateStatus(Phase.ANALYZE, round = autoRound)
                    updateStatus(Phase.COMPUTE, round = autoRound)
                    val res = try {
                        computePathFromBitmap(bitmap, sX, sY, oS)
                    } catch (e: Throwable) {
                        updateStatus(Phase.ERROR, round = autoRound, note = e.javaClass.simpleName); break
                    }
                    if (res == null) { updateStatus(Phase.NO_PATH, round = autoRound); break }
                    val isFallback   = res.second < 0
                    val trueCombo    = if (isFallback) -res.second else res.second
                    val path         = res.first
                    val gesturePhase = if (isFallback) Phase.FALLBACK else Phase.GESTURE
                    updateStatus(gesturePhase, combo = trueCombo, steps = path.size, round = autoRound,
                        note = if (isFallback) "ML未就緒" else "")
                    delay(200)
                    // startY 已是真實螢幕像素座標（含狀態列），dispatchGesture 同為螢幕座標，毋需再補償
                    val adjustedPath = path
                    val actualSteps  = (path.size - 1).coerceAtLeast(1)
                    val dur = (actualSteps.toLong() * currentGestureMs / currentSteps.toLong()).coerceAtLeast(200L)
                    isGestureRunning = true
                    val ok = accessibilityService.performOrbSwipe(adjustedPath, dur)
                    isGestureRunning = false
                    if (!ok) { updateStatus(Phase.ERROR, round = autoRound, note = "手勢失敗"); break }

                    // ── 輪間隔倒數 ──────────────────────────────────────
                    val waitEnd = System.currentTimeMillis() + currentRoundIntervalMs
                    while (isAutoRunning) {
                        val remaining = waitEnd - System.currentTimeMillis()
                        if (remaining <= 0L) break
                        val remSec = (remaining + 999L) / 1000L  // ceiling
                        updateStatus(Phase.SUCCESS, combo = trueCombo, steps = path.size,
                            round = autoRound, note = "下輪 ${remSec}s")
                        delay(minOf(500L, remaining))
                    }
                }

                // ── 退出 auto 模式：恢復正常 UI ─────────────────────────
                isAutoRunning = false
                autoBar.visibility = View.GONE
                if (autoRound > 0) {
                    updateStatus(Phase.STOPPED)
                    // 短暫顯示最終狀態後收起 statusCard
                    delay(2000)
                }
                statusCard.visibility = View.GONE
                refreshWindowLayout()
            }
        }

        btnMacro.setOnClickListener {
            if (macroListWindow == null) macroListWindow = MacroListWindow(context, accessibilityService, scope, prefs)
            macroListWindow?.show()
            toggleExpand()
        }
        btnStop.setOnClickListener { isAutoRunning = false; updateStatus(Phase.STOPPED) }
        btnSettings.setOnClickListener {
            isSettingsOpen = !isSettingsOpen
            settingsPanel.visibility = if (isSettingsOpen) View.VISIBLE else View.GONE
            refreshWindowLayout()
        }
        btnClose.setOnClickListener { hide() }

        menuLayout.apply {
            addView(btnPlay); addView(btnPreview); addView(btnAuto); addView(btnStop)
            addView(btnMacro); addView(btnSettings); addView(btnClose)
        }
        menuScrollView.addView(menuLayout)

        // ── 自動執行精簡列（auto 啟動時取代選單列）────────────────
        autoBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            background = mkBg()
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnAutoBarStop = createMenuButton("■", "#E74C3C")
        btnAutoBarStop.setOnClickListener { isAutoRunning = false }
        autoBar.addView(btnAutoBarStop)

        topRow.addView(toggleBtn)
        topRow.addView(menuScrollView)
        topRow.addView(autoBar)
    }

    private fun setupSettingsPanel() {
        settingsPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(24, 24, 24, 24)
            background = GradientDrawable().apply { setColor(Color.parseColor("#EE34495E")); cornerRadius = 16f }
        }

        val stepVal = TextView(context).apply { text = "$currentSteps"; setTextColor(Color.YELLOW); setPadding(16, 0, 16, 0) }
        val btnMinus = createMenuButton("-", "#E74C3C").apply { setOnClickListener {
            currentSteps = (currentSteps - 5).coerceAtLeast(5); stepVal.text = "$currentSteps"
            prefs.edit().putInt("steps", currentSteps).apply()
        } }
        val btnPlus = createMenuButton("+", "#2ECC71").apply { setOnClickListener {
            currentSteps = (currentSteps + 5).coerceAtMost(60); stepVal.text = "$currentSteps"
            prefs.edit().putInt("steps", currentSteps).apply()
        } }
        val rowStep = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(context).apply { text = "步數: "; setTextColor(Color.WHITE) })
            addView(btnMinus); addView(stepVal); addView(btnPlus)
        }

        val timeVal = TextView(context).apply { text = "${currentGestureMs / 1000}s"; setTextColor(Color.YELLOW); setPadding(16, 0, 16, 0) }
        val btnTimeMinus = createMenuButton("-", "#E74C3C").apply {
            setOnClickListener {
                currentGestureMs = (currentGestureMs - 1000L).coerceAtLeast(1000L)
                timeVal.text = "${currentGestureMs / 1000}s"
                prefs.edit().putLong("gestureMs", currentGestureMs).apply()
            }
        }
        val btnTimePlus = createMenuButton("+", "#2ECC71").apply {
            setOnClickListener {
                currentGestureMs = (currentGestureMs + 1000L).coerceAtMost(15000L)
                timeVal.text = "${currentGestureMs / 1000}s"
                prefs.edit().putLong("gestureMs", currentGestureMs).apply()
            }
        }
        val rowTime = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(context).apply { text = "轉珠時間: "; setTextColor(Color.WHITE) })
            addView(btnTimeMinus); addView(timeVal); addView(btnTimePlus)
        }

        val intervalVal = TextView(context).apply { text = "${currentRoundIntervalMs / 1000}s"; setTextColor(Color.YELLOW); setPadding(16, 0, 16, 0) }
        val btnIntervalMinus = createMenuButton("-", "#E74C3C").apply {
            setOnClickListener {
                currentRoundIntervalMs = (currentRoundIntervalMs - 1000L).coerceAtLeast(1000L)
                intervalVal.text = "${currentRoundIntervalMs / 1000}s"
                prefs.edit().putLong("roundIntervalMs", currentRoundIntervalMs).apply()
            }
        }
        val btnIntervalPlus = createMenuButton("+", "#2ECC71").apply {
            setOnClickListener {
                currentRoundIntervalMs = (currentRoundIntervalMs + 1000L).coerceAtMost(30000L)
                intervalVal.text = "${currentRoundIntervalMs / 1000}s"
                prefs.edit().putLong("roundIntervalMs", currentRoundIntervalMs).apply()
            }
        }
        val rowInterval = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(context).apply { text = "輪間隔: "; setTextColor(Color.WHITE) })
            addView(btnIntervalMinus); addView(intervalVal); addView(btnIntervalPlus)
        }

        val beamVal = TextView(context).apply { text = "$beamWidth"; setTextColor(Color.YELLOW); setPadding(16, 0, 16, 0) }
        val btnBeamMinus = createMenuButton("-", "#E74C3C").apply {
            setOnClickListener {
                beamWidth = (beamWidth - 50).coerceAtLeast(50)
                beamVal.text = "$beamWidth"
                prefs.edit().putInt("beamWidth", beamWidth).apply()
            }
        }
        val btnBeamPlus = createMenuButton("+", "#2ECC71").apply {
            setOnClickListener {
                beamWidth = (beamWidth + 50).coerceAtMost(500)
                beamVal.text = "$beamWidth"
                prefs.edit().putInt("beamWidth", beamWidth).apply()
            }
        }
        val rowBeam = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(context).apply { text = "搜尋廣度: "; setTextColor(Color.WHITE) })
            addView(btnBeamMinus); addView(beamVal); addView(btnBeamPlus)
        }

        val cb8Way = CheckBox(context).apply {
            text = "斜向移動 (8方向)"
            setTextColor(Color.WHITE)
            isChecked = is8Way
            setOnCheckedChangeListener { _, checked ->
                is8Way = checked
                prefs.edit().putBoolean("is8Way", checked).apply()
            }
        }

        val tvStrategy = TextView(context).apply { text = "計算策略："; setTextColor(Color.WHITE); setPadding(0, 16, 0, 8) }
        val rgStrategy = RadioGroup(context).apply { orientation = LinearLayout.VERTICAL }
        val rbS0 = RadioButton(context).apply { text = "一般高 Combo"; setTextColor(Color.WHITE); id = 0; isChecked = (strategyMode == 0) }
        val rbS1 = RadioButton(context).apply { text = "底層平鋪防黏珠"; setTextColor(Color.WHITE); id = 1; isChecked = (strategyMode == 1) }
        val rbS2 = RadioButton(context).apply { text = "直排4顆 (昇華技)"; setTextColor(Color.WHITE); id = 2; isChecked = (strategyMode == 2) }
        val rbS3 = RadioButton(context).apply { text = "ML 智能策略"; setTextColor(Color.WHITE); id = 3; isChecked = (strategyMode == 3) }
        rgStrategy.addView(rbS0); rgStrategy.addView(rbS1); rgStrategy.addView(rbS2); rgStrategy.addView(rbS3)

        // ── ML 屬性選擇面板（只在選擇 ML 模式時顯示）──
        val mlAttrPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(16, 4, 0, 4)
        }
        val tvAttr = TextView(context).apply {
            text = "屬性優先："; setTextColor(Color.parseColor("#BDC3C7"))
            textSize = 12f
        }
        val rgAttr = RadioGroup(context).apply { orientation = LinearLayout.HORIZONTAL }

        // Attribute labels and corresponding model filenames
        data class AttrInfo(val label: String, val color: String, val modelFile: String)
        val attrs = listOf(
            AttrInfo("通用", "#AAAAAA", "orb_policy_general.tflite"),
            AttrInfo("水",   "#5DADE2", "orb_policy_water.tflite"),
            AttrInfo("火",   "#E74C3C", "orb_policy_fire.tflite"),
            AttrInfo("木",   "#2ECC71", "orb_policy_wood.tflite"),
            AttrInfo("光",   "#F4D03F", "orb_policy_light.tflite"),
            AttrInfo("暗",   "#9B59B6", "orb_policy_dark.tflite"),
            AttrInfo("心",   "#F1948A", "orb_policy_heart.tflite"),
        )
        attrs.forEachIndexed { idx, attr ->
            RadioButton(context).apply {
                text = attr.label
                setTextColor(Color.parseColor(attr.color))
                id = idx
                isChecked = (attr.modelFile == selectedMLModel)
                textSize = 12f
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        val newModel = attr.modelFile
                        if (newModel != selectedMLModel) {
                            mlPlanner?.close(); mlPlanner = null  // force reload on next use
                            selectedMLModel = newModel
                            prefs.edit().putString("selectedMLModel", newModel).apply()
                        }
                    }
                }
            }.also { rgAttr.addView(it) }
        }
        mlAttrPanel.addView(tvAttr)
        mlAttrPanel.addView(rgAttr)

        mlAttrPanel.visibility = if (strategyMode == 3) View.VISIBLE else View.GONE
        rgStrategy.setOnCheckedChangeListener { _, id ->
            strategyMode = id
            prefs.edit().putInt("strategyMode", id).apply()
            mlAttrPanel.visibility = if (id == 3) View.VISIBLE else View.GONE
            refreshWindowLayout()
        }

        val tvStart = TextView(context).apply { text = "起手點位置："; setTextColor(Color.WHITE); setPadding(0, 16, 0, 8) }
        val rgStart = RadioGroup(context).apply { orientation = LinearLayout.HORIZONTAL }
        val rbP0 = RadioButton(context).apply { text = "全盤尋找"; setTextColor(Color.WHITE); id = 0; isChecked = (startMode == 0) }
        val rbP1 = RadioButton(context).apply { text = "左下角"; setTextColor(Color.WHITE); id = 1; isChecked = (startMode == 1) }
        val rbP2 = RadioButton(context).apply { text = "右下角"; setTextColor(Color.WHITE); id = 2; isChecked = (startMode == 2) }
        rgStart.addView(rbP0); rgStart.addView(rbP1); rgStart.addView(rbP2)
        rgStart.setOnCheckedChangeListener { _, id -> startMode = id; prefs.edit().putInt("startMode", id).apply() }

        // ── 校正工具區塊 ─────────────────────────────────────────────
        val tvCalibSection = TextView(context).apply {
            text = "校正工具："
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 8)
        }

        // 📷 建立樣板（原 top bar btnCalibrate）
        val btnCalib = Button(context).apply {
            text = "📷 建立樣板"
            setBackgroundColor(Color.parseColor("#8E44AD"))
            setTextColor(Color.WHITE)
            setOnClickListener { doTemplateCalibration() }
        }

        // 🔍 辨識計算（原 top bar btnAnalyze，只計算顯示路徑，不執行）
        val btnAnalyzeSetting = Button(context).apply {
            text = "🔍 辨識計算"
            setBackgroundColor(Color.parseColor("#3498DB"))
            setTextColor(Color.WHITE)
            setOnClickListener { analyzeAndComputePath(autoExecute = false) }
        }

        // ▶ 執行路徑（原 top bar btnPlay，執行上次計算的路徑）
        val btnPlaySetting = Button(context).apply {
            text = "▶ 執行路徑"
            setBackgroundColor(Color.parseColor("#2ECC71"))
            setTextColor(Color.WHITE)
            setOnClickListener { executeLastPath() }
        }

        val rowCalibActions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnCalib); addView(btnAnalyzeSetting); addView(btnPlaySetting)
        }

        // 🔍 調整辨識區域（開啟 BoardPreviewWindow 校正板）
        val btnPre = Button(context).apply {
            text = "🔍 調整辨識區域"
            setBackgroundColor(Color.parseColor("#2980B9"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (previewWindow == null) previewWindow = BoardPreviewWindow(context) { collectInitialSamples() }
                pathOverlayWindow.hide()
                previewWindow?.show()
                toggleExpand()
            }
        }

        settingsPanel.addView(rowStep)
        settingsPanel.addView(rowTime)
        settingsPanel.addView(rowInterval)
        settingsPanel.addView(rowBeam)
        settingsPanel.addView(cb8Way)
        settingsPanel.addView(tvStrategy); settingsPanel.addView(rgStrategy)
        settingsPanel.addView(mlAttrPanel)
        settingsPanel.addView(tvStart); settingsPanel.addView(rgStart)
        settingsPanel.addView(tvCalibSection)
        settingsPanel.addView(rowCalibActions)
        settingsPanel.addView(btnPre)
    }

    private fun setupResultRow() {
        val dp = context.resources.displayMetrics.density

        // 彩色圓點，顏色隨 Phase 變化
        statusDot = View(context).apply {
            val size = (14 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = (10 * dp).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(Phase.IDLE.color))
            }
        }

        // 主要狀態文字（粗體，顏色同圓點）
        statusLabel = TextView(context).apply {
            text = Phase.IDLE.label
            setTextColor(Color.parseColor(Phase.IDLE.color))
            textSize = 14f
            setTypeface(Typeface.DEFAULT_BOLD)
        }

        // 詳細資訊（Combo · 步數 · 第N輪），預設隱藏
        statusDetail = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 11f
            visibility = View.GONE
            setPadding((14 * dp).toInt(), (3 * dp).toInt(), 0, 0)
        }

        // 右側文字欄（主狀態 + 詳細）
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusLabel)
            addView(statusDetail)
        }

        // Card：圓點 + 文字欄，橫向排列，深色圓角背景
        statusCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DD1A252F"))
                cornerRadius = 14f
            }
            addView(statusDot)
            addView(textCol)
        }
    }

    private fun createMenuButton(t: String, c: String) = TextView(context).apply {
        text = t; setTextColor(Color.parseColor(c)); setBackgroundColor(Color.TRANSPARENT)
        setPadding(24, 16, 24, 16); textSize = 22f
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragAndSnapListener() {
        var iY = 0; var iTY = 0f; var isDragging = false
        toggleBtn.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { iY = params.y; iTY = e.rawY; isDragging = false; true }
                MotionEvent.ACTION_MOVE -> { val dy = e.rawY - iTY; if (abs(dy) > 10) { isDragging = true; params.y = iY + dy.toInt(); windowManager.updateViewLayout(rootLayout, params) }; true }
                MotionEvent.ACTION_UP -> { if (!isDragging) toggleExpand(); true }
                else -> false
            }
        }
    }

    private fun toggleExpand() {
        if (isAutoRunning) return  // 自動執行中不允許展開選單
        isExpanded = !isExpanded
        menuScrollView.visibility = if (isExpanded) View.VISIBLE else View.GONE
        statusCard.visibility = if (isExpanded) View.VISIBLE else View.GONE
        if (!isExpanded) { isSettingsOpen = false; settingsPanel.visibility = View.GONE }
        refreshWindowLayout()
    }

    private fun refreshWindowLayout() {
        rootLayout?.post {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            windowManager.updateViewLayout(rootLayout, params)
        }
    }

    fun updateStatus(
        phase: Phase,
        combo: Int = 0,
        steps: Int = 0,
        round: Int = 0,
        note: String = ""
    ) {
        statusCard.post {
            // 更新圓點顏色
            (statusDot.background as? GradientDrawable)?.setColor(Color.parseColor(phase.color))

            // 更新主要文字
            statusLabel.text = if (note.isNotEmpty()) "${phase.label}（$note）" else phase.label
            statusLabel.setTextColor(Color.parseColor(phase.color))

            // 更新詳細列（有值時才顯示）
            val parts = buildList {
                if (combo > 0) add("Combo $combo")
                if (steps > 0) add("步數 $steps")
                if (round > 0) add("第 $round 輪")
            }
            if (parts.isEmpty()) {
                statusDetail.visibility = View.GONE
            } else {
                statusDetail.text = parts.joinToString(" · ")
                statusDetail.visibility = View.VISIBLE
            }
        }
    }

    private fun getStatusBarHeight(): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
    }

    /** 選擇性載入樣板（HSV 為主，樣板僅備援；無樣板時仍可辨識） */
    private fun tryLoadTemplates() {
        val loaded = TemplateManager.loadTemplates(context)
        if (loaded.isNotEmpty()) BoardAnalyzer.templates.putAll(loaded)
    }

    /**
     * 核心計算（suspend）：給定已截好的 bitmap，辨識盤面並計算路徑。
     * 回傳 Pair(path, combo)，combo < 0 表示 ML 降級（trueCombo = -combo）。
     * 路徑為空時回傳 null。
     * bitmap 在此函式內部回收。
     */
    private suspend fun computePathFromBitmap(
        bitmap: Bitmap, sX: Int, sY: Int, oS: Int
    ): Pair<List<Pair<Float, Float>>, Int>? = withContext(Dispatchers.Default) {
        val analyzer = BoardAnalyzer()
        val board    = analyzer.analyzeBoard(bitmap, sX, sY, oS)
        val result: Pair<List<Pair<Float, Float>>, Int> = if (strategyMode == 3) {
            val ml = getOrCreateMLPlanner()
            if (ml != null) {
                analyzer.calculateMLPath(
                    board = board, orbSize = oS.toFloat(),
                    startX = sX.toFloat(), startY = sY.toFloat(),
                    mlPlanner = ml, steps = currentSteps, startMode = startMode
                )
            } else {
                val fb = analyzer.calculateOptimalPath(
                    board = board, orbSize = oS.toFloat(),
                    startX = sX.toFloat(), startY = sY.toFloat(),
                    is8Way = is8Way, strategyMode = 0,
                    startMode = startMode, steps = currentSteps,
                    beamWidth = beamWidth
                )
                Pair(fb.first, -fb.second.coerceAtLeast(1))
            }
        } else {
            analyzer.calculateOptimalPath(
                board = board, orbSize = oS.toFloat(),
                startX = sX.toFloat(), startY = sY.toFloat(),
                is8Way = is8Way, strategyMode = strategyMode,
                startMode = startMode, steps = currentSteps,
                beamWidth = beamWidth
            )
        }
        if (!bitmap.isRecycled) bitmap.recycle()
        result.takeIf { it.first.isNotEmpty() }
    }

    /**
     * 擷取截圖 → 辨識盤面 → 計算路徑，結果存入 lastCalculatedResult。
     * autoExecute = true 時，路徑計算完立即執行手勢（一鍵模式）；
     * autoExecute = false 時，只顯示路徑疊層供確認（分步模式）。
     */
    private fun analyzeAndComputePath(autoExecute: Boolean = false) {
        val bitmap = ScreenCaptureService.instance?.captureSnapshot()
        if (bitmap == null) { Toast.makeText(context, "擷取畫面失敗", Toast.LENGTH_SHORT).show(); return }
        val sX = prefs.getInt("startX", 0)
        val sY = prefs.getInt("startY", 1000)
        val oS = prefs.getInt("orbSize", 150)
        tryLoadTemplates()
        updateStatus(Phase.ANALYZE)
        scope.launch {
            try {
                updateStatus(Phase.COMPUTE)
                val res = computePathFromBitmap(bitmap, sX, sY, oS)
                if (res == null) {
                    lastCalculatedResult = null; pathOverlayWindow.hide(); updateStatus(Phase.NO_PATH)
                } else {
                    val isFallback = res.second < 0
                    val trueCombo  = if (isFallback) -res.second else res.second
                    val displayRes = if (isFallback) Pair(res.first, trueCombo) else res
                    lastCalculatedResult = displayRes
                    val phase = if (isFallback) Phase.FALLBACK else Phase.SUCCESS
                    val note  = if (isFallback) "ML未就緒" else ""
                    if (autoExecute) {
                        updateStatus(phase, combo = trueCombo, steps = displayRes.first.size, note = note)
                        executeLastPath()
                    } else {
                        pathOverlayWindow.show(displayRes.first)
                        updateStatus(phase, combo = trueCombo, steps = displayRes.first.size, note = note)
                    }
                }
            } catch (e: Throwable) { updateStatus(Phase.ERROR, note = e.javaClass.simpleName) }
        }
    }

    /** 執行上次計算的路徑；若無路徑則提示。 */
    private fun executeLastPath() {
        if (isGestureRunning) return
        val result = lastCalculatedResult
        if (result == null) {
            Toast.makeText(context, "請先辨識計算路徑", Toast.LENGTH_SHORT).show()
            return
        }
        val (path, combo) = result
        pathOverlayWindow.hide()
        isGestureRunning = true
        lastCalculatedResult = null
        scope.launch {
            delay(300)
            updateStatus(Phase.GESTURE, combo = combo, steps = path.size)
            // startY 已是真實螢幕像素座標（含狀態列），dispatchGesture 同為螢幕座標，毋需再補償
            val adjustedPath = path
            val actualSteps  = (path.size - 1).coerceAtLeast(1)
            val dur = (actualSteps.toLong() * currentGestureMs / currentSteps.toLong()).coerceAtLeast(200L)
            val success = accessibilityService.performOrbSwipe(adjustedPath, dur)
            isGestureRunning = false
            if (success) updateStatus(Phase.SUCCESS, combo = combo, steps = path.size)
            else         updateStatus(Phase.ERROR, note = "手勢失敗，請重試")
        }
    }

    /** 截圖後開啟樣板校正視窗。 */
    private fun doTemplateCalibration() {
        val bitmap = ScreenCaptureService.instance?.captureSnapshot()
        if (bitmap != null) {
            pathOverlayWindow.hide()
            recognitionOverlayWindow.hide()
            TemplateCalibrationWindow(context).show(bitmap)
        } else {
            Toast.makeText(context, "擷取畫面失敗", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 從校正板觸發：截圖 → 提取特徵 → 開啟辨識修正 overlay 讓使用者逐格指定屬性並存入資料庫。
     * 不依賴樣板（analyzeBoard 以 HSV 為主），辨識結果僅供參考，使用者可逐格覆蓋。
     */
    private fun collectInitialSamples() {
        val bitmap = ScreenCaptureService.instance?.captureSnapshot()
        if (bitmap == null) {
            Toast.makeText(context, "請先啟用螢幕擷取", Toast.LENGTH_SHORT).show()
            return
        }
        val sX = prefs.getInt("startX", 0)
        val sY = prefs.getInt("startY", 1000)
        val oS = prefs.getInt("orbSize", 150)

        previewWindow?.hide()
        updateStatus(Phase.ANALYZE, note = "採集中")
        scope.launch {
            try {
                val (board, features) = withContext(Dispatchers.Default) {
                    val analyzer = BoardAnalyzer()
                    val result = analyzer.analyzeBoard(bitmap, sX, sY, oS)
                    if (!bitmap.isRecycled) bitmap.recycle()
                    Pair(result, analyzer.lastFeatures)
                }
                pathOverlayWindow.hide()
                recognitionOverlayWindow.show(board, features, sX, sY, oS)
                updateStatus(Phase.PREVIEW, note = "點格子指定正確屬性")
            } catch (e: Throwable) {
                updateStatus(Phase.ERROR, note = e.javaClass.simpleName)
            }
        }
    }

    /**
     * Returns the shared [MLOrbPlanner] for [selectedMLModel], creating it on first call.
     * Returns null if the model file is not present in assets.
     */
    private fun getOrCreateMLPlanner(): MLOrbPlanner? {
        val existing = mlPlanner
        if (existing != null && existing.modelFile == selectedMLModel)
            return if (existing.isAvailable) existing else null
        existing?.close()
        val planner = MLOrbPlanner(context, selectedMLModel)
        mlPlanner = planner
        return if (planner.isAvailable) planner else null
    }

    fun hide() {
        isAutoRunning = false
        isGestureRunning = false
        scope.cancel()
        mlPlanner?.close()
        mlPlanner = null
        previewWindow?.hide()
        macroListWindow?.hide()
        macroListWindow = null
        pathOverlayWindow.hide()
        recognitionOverlayWindow.hide()
        rootLayout?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        rootLayout = null
    }
}