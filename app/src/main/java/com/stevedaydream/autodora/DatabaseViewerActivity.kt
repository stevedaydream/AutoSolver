package com.stevedaydream.autodora

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.stevedaydream.autodora.ui.theme.AutoDoraTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DatabaseViewerActivity : ComponentActivity() {

    // Compose-observable samples list — updated after reset / add
    private val samplesState = mutableStateOf<List<RecognitionDatabase.Sample>>(emptyList())
    private lateinit var db: RecognitionDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (RecognitionDatabase.instance == null) {
            RecognitionDatabase.instance = RecognitionDatabase(this)
        }
        db = RecognitionDatabase.instance!!
        samplesState.value = db.samples.toList()

        // Check which TFLite model files are present in assets
        val modelStatuses: List<Triple<String, String, Boolean>> =
            MLOrbPlanner.AVAILABLE_MODELS.map { (label, file) ->
                val ok = try { assets.open(file).close(); true } catch (_: Exception) { false }
                Triple(label, file, ok)
            }

        setContent {
            AutoDoraTheme {
                var showResetConfirm by remember { mutableStateOf(false) }
                var showAddDialog    by remember { mutableStateOf(false) }

                DatabaseViewerScreen(
                    samples       = samplesState.value,
                    modelStatuses = modelStatuses,
                    defaultCount  = RecognitionDatabase.DEFAULT_SAMPLES.size,
                    onExport      = { exportSamples(db) },
                    onResetClick  = { showResetConfirm = true }
                )

                // ── 確認重置對話框 ────────────────────────────────────────────
                if (showResetConfirm) {
                    val userCount = samplesState.value.size - RecognitionDatabase.DEFAULT_SAMPLES.size
                    AlertDialog(
                        onDismissRequest = { showResetConfirm = false },
                        title = { Text("重置資料庫") },
                        text  = {
                            Text("確定要清空所有使用者新增的樣本嗎？\n（共 $userCount 筆，預設樣本將保留）")
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    resetDb()
                                    showResetConfirm = false
                                    showAddDialog    = true   // 接著開啟手動指定對話框
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
                            ) { Text("確定清空") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
                        }
                    )
                }

                // ── 手動指定初始樣本對話框 ────────────────────────────────────
                if (showAddDialog) {
                    AddInitialSamplesDialog(
                        onDismiss = { showAddDialog = false },
                        onConfirm = { json ->
                            val err = addInitialSamples(json)
                            if (err == null) {
                                showAddDialog = false
                                Toast.makeText(this, "已新增初始樣本", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                }
            }
        }
    }

    // ── 重置 DB（清空使用者樣本）──────────────────────────────────────────────
    private fun resetDb() {
        db.resetUserSamples()
        samplesState.value = db.samples.toList()
        Toast.makeText(this, "資料庫已重置至預設樣本", Toast.LENGTH_SHORT).show()
    }

    // ── 解析並新增 JSON 樣本；成功回傳 null，失敗回傳錯誤訊息 ──────────────────
    private fun addInitialSamples(json: String): String? {
        return try {
            val arr = JSONArray(json.trim())
            for (i in 0 until arr.length()) {
                db.addSample(RecognitionDatabase.Sample.fromJson(arr.getJSONObject(i)))
            }
            samplesState.value = db.samples.toList()
            null
        } catch (e: Exception) {
            "解析錯誤：${e.message}"
        }
    }

    private fun exportSamples(db: RecognitionDatabase) {
        try {
            val arr = JSONArray()
            db.samples.forEachIndexed { index, sample ->
                arr.put(JSONObject().apply {
                    put("index",   index)
                    put("source",  if (index < RecognitionDatabase.DEFAULT_SAMPLES.size) "DEFAULT" else "USER")
                    put("type",    sample.type.name)
                    put("variant", sample.variant.toString())
                    put("d290",    sample.d290)
                    put("d280",    sample.d280)
                    put("d300",    sample.d300)
                    put("w130",    sample.w130)
                    put("l50",     sample.l50)
                    put("wt210",   sample.wt210)
                    put("h310",    sample.h310)
                    put("h320",    sample.h320)
                    put("f020",    sample.f020)
                    put("validPx", sample.validPx)
                })
            }

            val file = File(cacheDir, "recognition_db_export.json")
            file.writeText(arr.toString(2))

            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "AutoDora Recognition Database Export")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "匯出資料庫"))

        } catch (e: Exception) {
            Toast.makeText(this, "匯出失敗: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

// ── 屬性顏色 ─────────────────────────────────────────────────────────
private fun typeColor(type: BoardAnalyzer.OrbType): Color = when (type) {
    BoardAnalyzer.OrbType.FIRE    -> Color(0xFFE53935)
    BoardAnalyzer.OrbType.WATER   -> Color(0xFF1E88E5)
    BoardAnalyzer.OrbType.WOOD    -> Color(0xFF43A047)
    BoardAnalyzer.OrbType.LIGHT   -> Color(0xFFFDD835)
    BoardAnalyzer.OrbType.DARK    -> Color(0xFF8E24AA)
    BoardAnalyzer.OrbType.HEART   -> Color(0xFFE91E63)
    BoardAnalyzer.OrbType.UNKNOWN -> Color(0xFF757575)
}

private fun typeLabel(type: BoardAnalyzer.OrbType): String = when (type) {
    BoardAnalyzer.OrbType.FIRE    -> "火"
    BoardAnalyzer.OrbType.WATER   -> "水"
    BoardAnalyzer.OrbType.WOOD    -> "木"
    BoardAnalyzer.OrbType.LIGHT   -> "光"
    BoardAnalyzer.OrbType.DARK    -> "暗"
    BoardAnalyzer.OrbType.HEART   -> "心"
    BoardAnalyzer.OrbType.UNKNOWN -> "?"
}

// ── 主畫面 ────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DatabaseViewerScreen(
    samples: List<RecognitionDatabase.Sample>,
    modelStatuses: List<Triple<String, String, Boolean>>,
    defaultCount: Int,
    onExport: () -> Unit,
    onResetClick: () -> Unit
) {
    var filterVariant by remember { mutableStateOf("ALL") }
    var filterType    by remember { mutableStateOf("ALL") }

    val allTypes = BoardAnalyzer.OrbType.entries.map { it.name }

    val displayed = remember(samples, filterVariant, filterType) {
        samples.mapIndexed { index, s -> Pair(index, s) }.filter { (_, s) ->
            (filterVariant == "ALL" || s.variant.toString() == filterVariant) &&
            (filterType    == "ALL" || s.type.name            == filterType)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("樣本資料庫") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    TextButton(
                        onClick = onResetClick,
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = Color(0xFFB71C1C)
                        )
                    ) {
                        Text("重置資料庫", fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = onExport,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("匯出 JSON")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // ── 模型狀態列 ───────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("模型：", fontSize = 12.sp, color = Color.Gray)
                modelStatuses.forEach { (label, _, available) ->
                    val tint = if (available) Color(0xFF2E7D32) else Color(0xFF9E9E9E)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = tint.copy(alpha = 0.13f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = if (available) "●" else "○",
                                fontSize = 9.sp,
                                color = tint
                            )
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                color = tint,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // ── 統計列 ──────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatBadge("總計 ${samples.size}", MaterialTheme.colorScheme.primary)
                StatBadge("預設 $defaultCount", Color(0xFF546E7A))
                StatBadge("使用者 ${samples.size - defaultCount}", Color(0xFF00838F))
                StatBadge("顯示 ${displayed.size}", MaterialTheme.colorScheme.secondary)
            }

            // ── 過濾：Variant ────────────────────────────────────
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("類型：", fontSize = 13.sp, color = Color.Gray)
                listOf("ALL" to "全部", "A" to "A類（高隱寫）", "B" to "B類（一般）", "D" to "D類（特效珠）").forEach { (val_, label) ->
                    FilterChip(
                        selected = filterVariant == val_,
                        onClick  = { filterVariant = val_ },
                        label    = { Text(label, fontSize = 12.sp) }
                    )
                }
            }

            // ── 過濾：OrbType ────────────────────────────────────
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("屬性：", fontSize = 13.sp, color = Color.Gray)
                FilterChip(
                    selected = filterType == "ALL",
                    onClick  = { filterType = "ALL" },
                    label    = { Text("全部", fontSize = 12.sp) }
                )
                allTypes.forEach { t ->
                    FilterChip(
                        selected = filterType == t,
                        onClick  = { filterType = t },
                        label    = { Text(t, fontSize = 12.sp) }
                    )
                }
            }

            // ── 樣本列表 ─────────────────────────────────────────
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(displayed) { _, (originalIndex, sample) ->
                    SampleCard(
                        originalIndex = originalIndex,
                        sample = sample,
                        isDefault = originalIndex < defaultCount
                    )
                }
            }
        }
    }
}

// ── 手動指定初始樣本對話框 ────────────────────────────────────────────
@Composable
private fun AddInitialSamplesDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("指定初始樣本") },
        text  = {
            Column {
                Text(
                    "貼上 JSON 陣列（與「匯出 JSON」格式相同）。\n可貼入先前匯出的 recognition_db_export.json 內容：",
                    fontSize = 13.sp,
                    color = Color.Gray
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    placeholder = {
                        Text(
                            "[{\"type\":\"FIRE\",\"variant\":\"B\",\"d290\":0,...}]",
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 11.sp,
                            color      = Color.Gray
                        )
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize   = 11.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    maxLines = 20
                )
            }
        },
        confirmButton = {
            Button(
                onClick  = { onConfirm(text) },
                enabled  = text.isNotBlank()
            ) { Text("確認新增") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("跳過") }
        }
    )
}

// ── 統計 Badge ────────────────────────────────────────────────────────
@Composable
private fun StatBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

// ── 單一樣本 Card ─────────────────────────────────────────────────────
@Composable
private fun SampleCard(
    originalIndex: Int,
    sample: RecognitionDatabase.Sample,
    isDefault: Boolean
) {
    val color = typeColor(sample.type)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {

            // ── 標頭 ────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 屬性色塊
                Box(
                    modifier = Modifier
                        .background(color, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = typeLabel(sample.type),
                        color = if (sample.type == BoardAnalyzer.OrbType.LIGHT) Color.Black else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = sample.type.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.weight(1f))

                // Variant badge
                val variantColor = when (sample.variant) {
                    'A'  -> Color(0xFFF57C00)
                    'D'  -> Color(0xFF2E7D32)
                    else -> Color(0xFF0288D1)
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = variantColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "${sample.variant}類",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 11.sp,
                        color = variantColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(6.dp))

                // Source badge
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = (if (isDefault) Color(0xFF546E7A) else Color(0xFF00838F)).copy(alpha = 0.12f)
                ) {
                    Text(
                        text = if (isDefault) "DEFAULT" else "USER",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        color = if (isDefault) Color(0xFF546E7A) else Color(0xFF00838F),
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.width(6.dp))

                // 特效狀態 badge（僅 D 類顯示）
                if (sample.status != BoardAnalyzer.OrbStatus.NONE) {
                    val statusColor = when (sample.status) {
                        BoardAnalyzer.OrbStatus.BURN    -> Color(0xFFFF6D00)
                        BoardAnalyzer.OrbStatus.SHIELD  -> Color(0xFF00BCD4)
                        BoardAnalyzer.OrbStatus.POISON  -> Color(0xFF6A1B9A)
                        BoardAnalyzer.OrbStatus.STICKY  -> Color(0xFF8D6E63)
                        BoardAnalyzer.OrbStatus.FREEZE  -> Color(0xFF4FC3F7)
                        BoardAnalyzer.OrbStatus.WEATHER -> Color(0xFF78909C)
                        else                            -> Color.Gray
                    }
                    val statusLabel = when (sample.status) {
                        BoardAnalyzer.OrbStatus.BURN    -> "燃燒"
                        BoardAnalyzer.OrbStatus.SHIELD  -> "加護"
                        BoardAnalyzer.OrbStatus.POISON  -> "腐化"
                        BoardAnalyzer.OrbStatus.STICKY  -> "黏著"
                        BoardAnalyzer.OrbStatus.FREEZE  -> "冰凍"
                        BoardAnalyzer.OrbStatus.WEATHER -> "風化"
                        else                            -> ""
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = statusColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = statusLabel,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 10.sp,
                            color = statusColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }

                // 索引
                Text(
                    text = "#$originalIndex",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── 特徵值 ───────────────────────────────────────────
            val features = listOf(
                "d290" to sample.d290, "d280" to sample.d280, "d300" to sample.d300,
                "w130" to sample.w130, "l50"  to sample.l50,  "wt210" to sample.wt210,
                "h310" to sample.h310, "h320" to sample.h320, "f020"  to sample.f020,
                "validPx" to sample.validPx
            )

            // 每行 2 個特徵
            features.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pair.forEach { (name, value) ->
                        FeatureCell(name = name, value = value, modifier = Modifier.weight(1f))
                    }
                    // 如果這行只有 1 個（最後一行奇數），補空白
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

// ── 單一特徵格 ────────────────────────────────────────────────────────
@Composable
private fun FeatureCell(name: String, value: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surface,
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = name,
            fontSize = 11.sp,
            color = Color.Gray,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value.toString(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}
