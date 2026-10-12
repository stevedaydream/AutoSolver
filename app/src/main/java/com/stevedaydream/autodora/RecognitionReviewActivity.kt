package com.stevedaydream.autodora

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.*
import androidx.activity.ComponentActivity
import kotlinx.coroutines.*

class RecognitionReviewActivity : ComponentActivity() {
    private lateinit var settings: VisionTeacherSettings
    private lateinit var store: RecognitionSampleStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var filter = SampleReview.PENDING
    private var page = 0
    private var loadJob: Job? = null
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = VisionTeacherSettings(this)
        store = RecognitionSampleStore(this)
        if (RecognitionLearningEngine.instance == null) RecognitionLearningEngine.instance = RecognitionLearningEngine(applicationContext)
        showSettings()
    }

    private fun screen(title: String): LinearLayout {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).top else @Suppress("DEPRECATION") insets.systemWindowInsetTop
            val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).bottom else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            view.setPadding(dp(16), top + dp(12), dp(16), bottom + dp(12)); insets
        }
        setContentView(scroll)
        content.addView(TextView(this).apply { text = title; textSize = 24f; setPadding(0, dp(8), 0, dp(12)) })
        return content
    }

    private fun LinearLayout.text(value: String) = TextView(this@RecognitionReviewActivity).apply {
        text = value; textSize = 14f; setPadding(0, dp(8), 0, dp(8)); addView(this)
    }

    private fun LinearLayout.button(label: String, action: () -> Unit) = Button(this@RecognitionReviewActivity).apply {
        text = label; setOnClickListener { action() }; addView(this)
    }

    private fun LinearLayout.field(label: String, initial: String, secret: Boolean = false) = EditText(this@RecognitionReviewActivity).apply {
        hint = label; setText(initial); isSingleLine = true
        if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun showSettings() {
        loadJob?.cancel()
        val content = screen("AI 辨識與待審資料庫")
        content.button("檢視／審核樣本") { page = 0; showQueue() }
        content.text("辨識時背景收錄，每分鐘最多一個盤面。模型只提供建議；人工確認後，本機才使用相似圖片樣本。")
        val enabled = CheckBox(this).apply { text = "啟用背景收錄"; isChecked = settings.enabled; content.addView(this) }
        val game = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL; content.addView(this) }
        GameProfile.entries.forEach { profile ->
            game.addView(RadioButton(this).apply { text = profile.name; id = profile.ordinal + 1; isChecked = settings.game == profile })
        }
        val endpoint = content.field("llama.cpp 端點，例如 http://主機:8080", settings.endpoint)
        val model = content.field("本機模型名稱（空白則自動查詢）", settings.model)
        val localKey = content.field("本機 API key（留白保留既有）", "", true)
        content.text("Gemini 備援只上傳裁切盤面；連線、格式、完整性失敗或結果含不確定格時，嘗試一次備援。")
        val cloud = CheckBox(this).apply { text = "啟用 Gemini 雲端備援"; isChecked = settings.cloudEnabled; content.addView(this) }
        val geminiModel = content.field("Gemini 圖像理解模型名稱", settings.geminiModel)
        val geminiKey = content.field("Gemini API key（留白保留既有）", "", true)
        val status = content.text(settings.prefs.getString("lastStatus", "尚未收錄") ?: "尚未收錄")
        fun save(): Boolean = try {
            val url = endpoint.text.toString().trim().trimEnd('/')
            if (enabled.isChecked) {
                val parsed = java.net.URL(url)
                require(parsed.protocol in listOf("http", "https") && parsed.userInfo == null) { "請輸入 http／https 端點" }
            }
            if (localKey.text.isNotBlank()) settings.setSecret("localKey", localKey.text.toString())
            if (geminiKey.text.isNotBlank()) settings.setSecret("geminiKey", geminiKey.text.toString())
            if (cloud.isChecked) {
                require(settings.secret("geminiKey").isNotBlank()) { "請輸入 Gemini API key" }
                require(geminiModel.text.toString().matches(Regex("[a-zA-Z0-9._-]+"))) { "請輸入 Gemini 模型名稱" }
            }
            settings.endpoint = url; settings.model = model.text.toString(); settings.geminiModel = geminiModel.text.toString()
            settings.game = GameProfile.entries[game.checkedRadioButtonId - 1]
            settings.enabled = enabled.isChecked; settings.cloudEnabled = cloud.isChecked
            localKey.text.clear(); geminiKey.text.clear()
            status.text = "設定已儲存"; true
        } catch (e: Exception) { status.text = "設定未儲存：${e.message}"; false }
        content.button("儲存設定") { save() }
        content.button("測試本機連線／取得模型名稱") {
            settings.endpoint = endpoint.text.toString()
            if (localKey.text.isNotBlank()) { settings.setSecret("localKey", localKey.text.toString()); localKey.text.clear() }
            scope.launch {
                status.text = "正在查詢本機模型…"
                try { model.setText(withContext(Dispatchers.IO) { VisionTeacherClient(settings).discoverLocalModel() }); status.text = "連線正常，請按儲存設定" }
                catch (e: Exception) { status.text = "連線失敗：${e.message}" }
            }
        }
        content.button("查詢 Gemini 可用模型") {
            if (geminiKey.text.isNotBlank()) { settings.setSecret("geminiKey", geminiKey.text.toString()); geminiKey.text.clear() }
            scope.launch {
                status.text = "正在查詢 Gemini 模型…"
                try {
                    val models = withContext(Dispatchers.IO) { VisionTeacherClient(settings).discoverGeminiModels() }
                    check(models.isNotEmpty()) { "沒有可用模型" }
                    AlertDialog.Builder(this@RecognitionReviewActivity).setTitle("選擇支援圖像理解的模型")
                        .setItems(models.toTypedArray()) { _, index -> geminiModel.setText(models[index]) }.show()
                    status.text = "查詢完成，選擇後請儲存設定"
                } catch (e: Exception) { status.text = "查詢失敗：${e.message}" }
            }
        }
        content.button("清除 Gemini 金鑰並關閉備援") {
            settings.setSecret("geminiKey", ""); settings.cloudEnabled = false; cloud.isChecked = false
            geminiKey.text.clear(); status.text = "已清除 Gemini 金鑰"
        }
        content.button("重新讀取收錄狀態") { status.text = settings.prefs.getString("lastStatus", "尚未收錄") }
        content.button("返回主畫面") { finish() }
    }

    private fun showQueue() {
        loadJob?.cancel()
        val content = screen("${settings.game.name} · 辨識樣本")
        content.button("辨識設定") { showSettings() }
        val loading = content.text("讀取樣本中…")
        val game = settings.game
        loadJob = scope.launch {
            val all = withContext(Dispatchers.IO) { store.list().filter { it.game == game } }
            content.removeView(loading)
            renderQueue(content, all)
        }
    }

    private fun renderQueue(content: LinearLayout, all: List<RecognitionReviewSample>) {
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; content.addView(this) }
        SampleReview.entries.forEach { state ->
            val label = when (state) { SampleReview.PENDING -> "待審"; SampleReview.CONFIRMED -> "已確認"; SampleReview.REJECTED -> "已拒絕" }
            filters.button("$label ${all.count { it.review == state }}") { filter = state; page = 0; showQueue() }
        }
        val samples = all.filter { it.review == filter }
        page = page.coerceIn(0, ((samples.size - 1) / 10).coerceAtLeast(0))
        content.text("${filter.name} · 第 ${page + 1} 頁。確認樣本僅用於相似圖片比對，尚未訓練通用視覺模型。")
        if (samples.isEmpty()) content.text("目前沒有樣本。啟用收錄後，在遊戲畫面執行辨識即可收集。")
        for (sample in samples.drop(page * 10).take(10)) {
            content.addView(ImageView(this).apply {
                setImageBitmap(BitmapFactory.decodeFile(store.image(sample.id).absolutePath))
                layoutParams = LinearLayout.LayoutParams(dp(110), dp(110))
            })
            val annotation = sample.confirmed ?: sample.suggested
            content.text("第 ${sample.row + 1} 列／${sample.col + 1} 欄 · ${sample.provider}\n本機：${sample.localType} → 建議：${sample.suggested.type}\n目前：${annotation.type}／${annotation.summary()}${if (sample.suggested.uncertain) "（模型不確定）" else ""}")
            content.button("檢查／修正此樣本") { showEditor(sample) }
        }
        if (page > 0) content.button("上一頁") { page--; showQueue() }
        if ((page + 1) * 10 < samples.size) content.button("下一頁") { page++; showQueue() }
        content.button("重新整理") { showQueue() }
    }

    private fun showEditor(sample: RecognitionReviewSample) {
        loadJob?.cancel()
        val content = screen("確認屬性與多重標記")
        val current = sample.confirmed ?: sample.suggested
        content.addView(ImageView(this).apply {
            setImageBitmap(BitmapFactory.decodeFile(store.image(sample.id).absolutePath))
            layoutParams = LinearLayout.LayoutParams(dp(180), dp(180))
        })
        content.text("請對照圖片確認；沒有看到的標記取消勾選。\n來源：${sample.provider}\n模型：${sample.model}\n${sample.localFailure?.let { "本機備援原因：$it" } ?: ""}")
        val types = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; content.addView(this) }
        val names = listOf("水", "火", "木", "光", "暗", "心", "未知")
        BoardAnalyzer.OrbType.entries.forEach { type ->
            types.addView(RadioButton(this).apply { id = type.ordinal + 1; text = names[type.ordinal]; isChecked = current.type == type })
        }
        val marks = OrbMark.entries.associateWith { mark ->
            CheckBox(this).apply { text = mark.label; isChecked = mark in current.marks; content.addView(this) }
        }
        val races = content.field("種族標記，以逗號分隔；沒有則留白", current.races.joinToString(","))
        val message = content.text("確認後才會提供給本機辨識。")
        content.button("確認標籤並啟用") {
            try {
                val raceSet = races.text.toString().split(',', '，', '、').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                require(raceSet.size <= 4 && raceSet.all { it.length <= 20 }) { "種族最多四個，每個最多 20 字" }
                val label = OrbAnnotation(BoardAnalyzer.OrbType.entries[types.checkedRadioButtonId - 1], marks.filterValues { it.isChecked }.keys, raceSet)
                store.review(sample, label); showQueue()
            } catch (e: Exception) { message.text = "未啟用：${e.message}" }
        }
        content.button("拒絕／停用此樣本") { store.review(sample, null); showQueue() }
        content.button("刪除圖片與樣本") {
            AlertDialog.Builder(this).setMessage("確定刪除此樣本及圖片？").setPositiveButton("刪除") { _, _ -> store.delete(sample); showQueue() }
                .setNegativeButton("取消", null).show()
        }
        content.button("返回清單") { showQueue() }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
