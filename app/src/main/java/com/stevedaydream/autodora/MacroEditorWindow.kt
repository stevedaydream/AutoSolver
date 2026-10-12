package com.stevedaydream.autodora

import android.content.ClipData
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.text.InputType
import android.view.*
import android.widget.*
import kotlinx.coroutines.*

class MacroEditorWindow(
    private val context: Context,
    private val scope: CoroutineScope,
    private val macro: Macro,
    private val onClose: () -> Unit
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dp = context.resources.displayMetrics.density
    private val metrics = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }
    private var actions = macro.actions.toMutableList()
    private var selected = 0
    private data class State(val actions: List<MacroAction>, val selected: Int)
    private val undo = ArrayDeque<State>()
    private val redo = ArrayDeque<State>()
    private val newImages = mutableSetOf<String>()
    private lateinit var root: LinearLayout
    private lateinit var rows: LinearLayout
    private lateinit var list: ScrollView
    private lateinit var settings: LinearLayout
    private lateinit var status: TextView
    private lateinit var preview: MacroGestureView
    private lateinit var undoButton: TextView
    private lateinit var redoButton: TextView
    private var playback: Job? = null
    private var elapsed = 0L
    private var paused = false
    private var continuePreview = false
    private var dragFrom = -1
    private var closed = false
    private var playbackVersion = 0

    fun show() {
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(12), px(12), px(12))
            background = OverlayUi.rounded(context, OverlayUi.SURFACE, 18f)
        }
        val header = bar(root)
        header.addView(label("編輯：${macro.name}", 16f), LinearLayout.LayoutParams(0, -2, 1f))
        button(header, "取消") { close() }
        button(header, "儲存") {
            try {
                MacroStorage.save(context, macro.copy(actions = actions.toList()))
                MacroImages.discard(context, macro.actions.mapNotNull { it.preview?.image })
                close()
            } catch (e: Exception) { message("儲存失敗：${e.message}") }
        }
        val tools = scrollingBar(root)
        undoButton = button(tools, "復原") { restore(undo, redo) }
        redoButton = button(tools, "重做") { restore(redo, undo) }
        button(tools, "＋點擊") { add(MacroAction.Tap(metrics.widthPixels / 2f, metrics.heightPixels / 2f)) }
        button(tools, "＋滑動") { add(MacroAction.Swipe(listOf(metrics.widthPixels * .3f to metrics.heightPixels * .5f, metrics.widthPixels * .7f to metrics.heightPixels * .5f), 500)) }
        button(tools, "＋等待") { add(MacroAction.Wait(1000)) }
        status = label("", 12f)
        root.addView(status)
        preview = MacroGestureView(context).apply { onEdit = { replacement -> change { actions[selected] = replacement } } }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 4f))
        val controls = scrollingBar(root)
        button(controls, "單步預覽") { startPlayback(false) }
        button(controls, "整份預覽") { startPlayback(true) }
        button(controls, "暫停／繼續") { if (playback != null) { paused = !paused; status.text = if (paused) "預覽已暫停" else "預覽繼續" } }
        button(controls, "下一步") {
            val running = continuePreview
            stopPlayback()
            if (selected < actions.lastIndex) { selected++; refresh(); if (running) startPlayback(true, false) }
        }
        list = ScrollView(context)
        rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        list.addView(rows)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 2.7f))
        val settingsScroll = ScrollView(context)
        settings = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        settingsScroll.addView(settings)
        root.addView(settingsScroll, LinearLayout.LayoutParams(-1, 0, 2.5f))
        val params = WindowManager.LayoutParams(
            (metrics.widthPixels * .96f).toInt(), (metrics.heightPixels * .92f).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER; dimAmount = .6f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        wm.addView(root, params)
        refresh()
    }

    private fun px(value: Int) = (value * dp).toInt()
    private fun label(text: String, size: Float = 13f) = TextView(context).apply {
        this.text = text; textSize = size; setTextColor(Color.parseColor(OverlayUi.TEXT_HI))
        setPadding(px(5), px(4), px(5), px(4))
    }
    private fun bar(parent: LinearLayout) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        parent.addView(this)
    }
    private fun scrollingBar(parent: LinearLayout): LinearLayout {
        val scroll = HorizontalScrollView(context)
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        scroll.addView(row); parent.addView(scroll)
        return row
    }
    private fun button(parent: LinearLayout, text: String, action: () -> Unit): TextView =
        OverlayUi.pillButton(context, text, textColor = OverlayUi.ACCENT).also {
            it.setOnClickListener { action() }; parent.addView(it)
        }
    private fun message(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    private fun snapshot() = State(actions.toList(), selected)
    private fun change(block: () -> Unit) {
        stopPlayback(); undo.addLast(snapshot()); redo.clear(); block()
        selected = selected.coerceIn(0, actions.lastIndex.coerceAtLeast(0)); refresh()
    }
    private fun restore(from: ArrayDeque<State>, to: ArrayDeque<State>) {
        if (from.isEmpty()) return
        stopPlayback(); to.addLast(snapshot())
        val state = from.removeLast(); actions = state.actions.toMutableList(); selected = state.selected
        refresh()
    }
    private fun add(action: MacroAction) {
        val nearby = nearbyFrame(selected)
        if (action !is MacroAction.Wait) action.preview = nearby
        change { val at = if (actions.isEmpty()) 0 else selected + 1; actions.add(at, action); selected = at }
    }
    private fun nearbyFrame(index: Int): MacroPreview? = actions.indices.sortedBy { kotlin.math.abs(it - index) }
        .firstNotNullOfOrNull { actions[it].preview }

    private fun description(action: MacroAction): String = when (action) {
        is MacroAction.Tap -> "點擊 (${action.x.toInt()}, ${action.y.toInt()}) · ${MacroTime.seconds(action.holdMs)} 秒"
        is MacroAction.Swipe -> "滑動 · ${action.path.size} 個路徑點 · ${MacroTime.seconds(action.durationMs)} 秒"
        is MacroAction.Wait -> "等待 ${MacroTime.seconds(action.ms)} 秒"
        is MacroAction.AutoBoard -> "自動解盤 · ${action.steps} 步 · 策略 ${action.strategyMode}" +
            if (action.shapeGoals.isEmpty()) "" else " · ${action.shapeGoals.size} 個版型"
        is MacroAction.UseSkill -> "成員 ${action.memberSlot + 1} · 技能 ${action.skillNumber}" + if (action.hasStage2) " · 含第二段" else ""
        is MacroAction.FixedBoard -> "固定盤面 · ${MacroTime.seconds(action.durationMs)} 秒"
    }

    private fun refresh() {
        rows.removeAllViews()
        undoButton.isEnabled = undo.isNotEmpty(); redoButton.isEnabled = redo.isNotEmpty()
        undoButton.alpha = if (undoButton.isEnabled) 1f else .35f
        redoButton.alpha = if (redoButton.isEnabled) 1f else .35f
        actions.forEachIndexed { index, action ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = OverlayUi.rounded(context, if (index == selected) OverlayUi.ACCENT_CHIP else OverlayUi.CHIP, 8f)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(3) }
            }
            val handle = label("☰", 18f)
            row.addView(handle)
            handle.setOnLongClickListener {
                stopPlayback(); dragFrom = index
                it.startDragAndDrop(ClipData.newPlainText("macro-step", index.toString()), View.DragShadowBuilder(row), this, 0)
            }
            row.setOnDragListener { _, event ->
                if (event.localState !== this) false else {
                    when (event.action) {
                        DragEvent.ACTION_DRAG_ENTERED -> row.alpha = .5f
                        DragEvent.ACTION_DRAG_EXITED -> row.alpha = 1f
                        DragEvent.ACTION_DRAG_LOCATION -> {
                            val location = IntArray(2); row.getLocationOnScreen(location)
                            val listLocation = IntArray(2); list.getLocationOnScreen(listLocation)
                            val y = location[1] + event.y - listLocation[1]
                            if (y < px(32)) list.smoothScrollBy(0, -px(30))
                            if (y > list.height - px(32)) list.smoothScrollBy(0, px(30))
                        }
                        DragEvent.ACTION_DROP -> if (dragFrom in actions.indices && dragFrom != index) {
                            change { val moved = actions.removeAt(dragFrom); actions.add(index, moved); selected = index }
                        }
                        DragEvent.ACTION_DRAG_ENDED -> { row.alpha = 1f; dragFrom = -1 }
                    }
                    true
                }
            }
            row.addView(label("${index + 1}. ${description(action)}").apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { stopPlayback(); selected = index; refresh(); preview.animate(duration(action)) }
            })
            button(row, "複製") { change { actions.add(index + 1, MacroAction.fromJson(action.toJson())); selected = index + 1 } }
            button(row, "刪除") { change { actions.removeAt(index) } }
            rows.addView(row)
        }
        settings.removeAllViews()
        val action = actions.getOrNull(selected)
        status.text = if (action == null) "尚無動作，請新增步驟" else "${selected + 1} / ${actions.size}　${description(action)}"
        preview.visibility = if (action == null) View.INVISIBLE else View.VISIBLE
        if (action == null) return
        preview.display(action, metrics.widthPixels, metrics.heightPixels,
            if (action is MacroAction.Wait) nearbyFrame(selected) else action.preview)
        preview.redrawPath = false
        val options = scrollingBar(settings)
        if (action !is MacroAction.Wait) button(options, "補上目前截圖") { capture() }
        if (action is MacroAction.Swipe || action is MacroAction.FixedBoard) {
            button(options, "拖曳起終點") { stopPlayback(); preview.redrawPath = false; status.text = "拖曳預覽圖上的起點或終點" }
            button(options, "重畫路徑") { stopPlayback(); preview.redrawPath = true; status.text = "在預覽圖上按住並畫出完整路徑" }
        }
        when (action) {
            is MacroAction.Tap -> {
                settings.addView(label("拖曳預覽圖可修改點擊位置"))
                coordinates(action.x, action.y) { x, y -> action.copy(x = x, y = y).withPreview(action) }
                timeField("按住秒數", action.holdMs) { action.copy(holdMs = it).withPreview(action) }
            }
            is MacroAction.Swipe -> timeField("滑動秒數", action.durationMs) { action.copy(durationMs = it).withPreview(action) }
            is MacroAction.Wait -> timeField("等待秒數", action.ms, true) { MacroAction.Wait(it) }
            is MacroAction.FixedBoard -> {
                settings.addView(label("盤面一致時走儲存路徑，不一致時改用自動解盤"))
                timeField("滑動秒數", action.durationMs) { action.copy(durationMs = it).withPreview(action) }
            }
            is MacroAction.AutoBoard -> settings.addView(label("執行時依盤面計算；預覽不執行解盤。按「下一步」確認並繼續。"))
            is MacroAction.UseSkill -> settings.addView(label("預覽顯示技能點擊位置" + if (action.hasStage2) "與第二段目標；可拖曳修改" else "；可拖曳修改"))
        }
    }

    private fun MacroAction.withPreview(source: MacroAction): MacroAction = also { preview = source.preview }
    private fun input(value: String) = EditText(context).apply {
        setText(value); setTextColor(Color.WHITE); textSize = 14f; setSingleLine()
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    }
    private fun timeField(title: String, ms: Long, zero: Boolean = false, replace: (Long) -> MacroAction) {
        val row = bar(settings); row.addView(label(title))
        val field = input(MacroTime.seconds(ms)); row.addView(field)
        button(row, "套用") {
            val value = MacroTime.milliseconds(field.text.toString(), zero)
            val max = if (zero) 86_400_000L else android.accessibilityservice.GestureDescription.getMaxGestureDuration()
            if (value == null || value > max) field.error = "請輸入${if (zero) "0" else "0.001"}～${MacroTime.seconds(max)} 秒，最多三位小數"
            else change { actions[selected] = replace(value) }
        }
    }
    private fun coordinates(x: Float, y: Float, replace: (Float, Float) -> MacroAction) {
        val row = bar(settings); row.addView(label("X")); val fx = input(x.toInt().toString()); row.addView(fx)
        row.addView(label("Y")); val fy = input(y.toInt().toString()); row.addView(fy)
        button(row, "套用") {
            val xx = fx.text.toString().toFloatOrNull(); val yy = fy.text.toString().toFloatOrNull()
            val frame = actions[selected].preview
            if (xx == null || yy == null || !xx.isFinite() || !yy.isFinite() || xx < 0 || yy < 0 || xx >= (frame?.width ?: metrics.widthPixels) || yy >= (frame?.height ?: metrics.heightPixels))
                message("座標需在畫面範圍內")
            else change { actions[selected] = replace(xx, yy) }
        }
    }

    private fun capture() {
        stopPlayback()
        val target = selected
        val params = root.layoutParams as WindowManager.LayoutParams
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        wm.updateViewLayout(root, params)
        root.visibility = View.INVISIBLE
        scope.launch {
            try {
                delay(180)
                val frame = withContext(Dispatchers.IO) { MacroImages.capture(context) }
                if (frame != null) {
                    newImages.add(frame.image)
                    if (!closed) change { actions[target] = MacroAction.fromJson(actions[target].toJson()).also { it.preview = frame } }
                    else MacroImages.discard(context, listOf(frame.image))
                } else if (!closed) message("無法截圖，請先在主畫面啟用螢幕擷取")
            } catch (e: Exception) { if (!closed) message("截圖失敗：${e.message}") }
            finally { if (!closed) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                wm.updateViewLayout(root, params)
                root.visibility = View.VISIBLE
            } }
        }
    }

    private fun duration(action: MacroAction): Long = when (action) {
        is MacroAction.Tap -> action.holdMs.coerceAtLeast(1)
        is MacroAction.Swipe -> action.durationMs
        is MacroAction.FixedBoard -> action.durationMs
        is MacroAction.Wait -> action.ms
        is MacroAction.UseSkill -> if (action.hasStage2) 1200 else 600
        is MacroAction.AutoBoard -> 0
    }
    private fun startPlayback(all: Boolean, fromStart: Boolean = true) {
        stopPlayback()
        if (actions.isEmpty()) return
        continuePreview = all
        val version = playbackVersion
        if (all && fromStart) selected = 0
        refresh(); preview.editable = false
        playback = scope.launch {
            try {
                do {
                    val action = actions[selected]
                    refresh(); preview.editable = false
                    list.smoothScrollTo(0, rows.getChildAt(selected).top)
                    if (action is MacroAction.AutoBoard) {
                        status.text = "自動解盤：執行時依盤面計算。請按「下一步」繼續。"
                        return@launch
                    }
                    elapsed = 0
                    val total = duration(action)
                    while (elapsed < total) {
                        if (!paused) {
                            preview.seek(elapsed.toFloat() / total.coerceAtLeast(1))
                            if (action is MacroAction.Wait) status.text = "等待剩餘 ${MacroTime.seconds(total - elapsed)} 秒"
                            delay(25); elapsed += 25
                        } else delay(25)
                    }
                    preview.stop()
                    if (!all || selected == actions.lastIndex) { continuePreview = false; break }
                    selected++
                } while (true)
            } finally { if (version == playbackVersion) { playback = null; preview.editable = true } }
        }
    }
    private fun stopPlayback() {
        playbackVersion++
        playback?.cancel(); playback = null; paused = false
        continuePreview = false
        if (::preview.isInitialized) { preview.stop(); preview.editable = true }
    }
    private fun close() {
        dismiss()
        onClose()
    }
    fun dismiss() {
        if (closed) return
        closed = true; stopPlayback(); preview.release()
        if (root.isAttachedToWindow) wm.removeView(root)
        MacroImages.discard(context, newImages)
    }
}
