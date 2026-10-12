package com.stevedaydream.autodora

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import com.stevedaydream.autodora.DatabaseViewerActivity
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.stevedaydream.autodora.ui.theme.AutoDoraTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_CHECK_UPDATE = "check_update"
    }

    private var updateCheckRequest by mutableStateOf(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CHECK_UPDATE, false)) updateCheckRequest++
    }

    // --- 修改開頭：新增 startMediaProjection 以處理截圖權限請求的回傳結果 ---
    // --- 修改開頭：獲取真實硬體螢幕解析度，解決截圖在記憶體中歪斜或偏移的問題 ---
    private val startMediaProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)

                val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                val width: Int
                val height: Int

                // 針對不同 Android 版本取得包含狀態列與導航列的「真實硬體解析度」
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bounds = windowManager.currentWindowMetrics.bounds
                    width = bounds.width()
                    height = bounds.height()
                } else {
                    val metrics = android.util.DisplayMetrics()
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.getRealMetrics(metrics)
                    width = metrics.widthPixels
                    height = metrics.heightPixels
                }

                putExtra("WIDTH", width)
                putExtra("HEIGHT", height)
                putExtra("DENSITY", resources.displayMetrics.densityDpi)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            Toast.makeText(this, "Screen Capture Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }
    // --- 修改結尾 ---

    // --- 修改開頭：將散落的 UI 程式碼整合進 onCreate 的 setContent 中，並修正 Settings 參照 ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_CHECK_UPDATE, false)) updateCheckRequest++
        enableEdgeToEdge()
        setContent {
            AutoDoraTheme {
                // ── GitHub Release 自動更新檢查 ──
                var updateInfo by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }
                var checkingUpdate by remember { mutableStateOf(false) }
                var updateStatus by remember { mutableStateOf("") }
                val currentVersion = remember {
                    try {
                        packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
                    } catch (e: Exception) { "0" }
                }
                LaunchedEffect(updateCheckRequest) {
                    checkingUpdate = true
                    if (updateCheckRequest > 0) updateStatus = "正在檢查更新…"
                    val latest = UpdateChecker.fetchLatest()
                    checkingUpdate = false
                    if (latest != null && UpdateChecker.isNewer(latest.versionName, currentVersion)) {
                        updateInfo = latest
                        updateStatus = "有新版本 v${latest.versionName}"
                    } else if (updateCheckRequest > 0) {
                        updateStatus = if (latest == null) "無法檢查更新，請確認網路後重試" else "目前已是最新版本"
                    }
                }
                updateInfo?.let { info ->
                    AlertDialog(
                        onDismissRequest = { updateInfo = null },
                        title = { Text("發現新版本 v${info.versionName}") },
                        text = {
                            Text(
                                if (info.notes.isBlank()) "有新版本可供更新。"
                                else info.notes.take(600)
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                if (info.apkUrl != null) {
                                    UpdateChecker.downloadAndInstall(
                                        this@MainActivity, info.apkUrl, info.versionName
                                    )
                                    Toast.makeText(
                                        this@MainActivity,
                                        "下載中，完成後會跳出安裝畫面",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)))
                                }
                                updateInfo = null
                            }) { Text("立即更新") }
                        },
                        dismissButton = {
                            TextButton(onClick = { updateInfo = null }) { Text("稍後") }
                        }
                    )
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Welcome to Auto Dora",
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.padding(bottom = 32.dp)
                        )

                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:$packageName")
                                    )
                                    startActivity(intent)
                                } else {
                                    Toast.makeText(this@MainActivity, "Overlay Permission Already Granted!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            Text("1. Request Overlay Permission")
                        }

                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                startActivity(intent)
                            },
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            Text("2. Open Accessibility Settings")
                        }

                        Button(
                            onClick = {
                                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                startMediaProjection.launch(mpm.createScreenCaptureIntent())
                            },
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            Text("3. Start Screen Capture")
                        }

                        Button(
                            onClick = {
                                startActivity(Intent(this@MainActivity, DatabaseViewerActivity::class.java))
                            }
                        ) {
                            Text("4. View Database")
                        }
                        Text(
                            text = "目前版本 v$currentVersion",
                            modifier = Modifier.padding(top = 16.dp)
                        )
                        Button(
                            onClick = { updateCheckRequest++ },
                            enabled = !checkingUpdate
                        ) {
                            Text(if (checkingUpdate) "檢查中…" else "檢查更新")
                        }
                        if (updateStatus.isNotBlank()) Text(updateStatus)
                    }
                }
            }
        }
    }
    // --- 修改結尾 ---
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    AutoDoraTheme { Greeting("Android") }
}
