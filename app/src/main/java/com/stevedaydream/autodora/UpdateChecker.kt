package com.stevedaydream.autodora

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Release 自動更新：
 * 查詢 /releases/latest → 比對 versionName → DownloadManager 下載 APK → 喚起安裝。
 * Release 由 release.bat 建立，tag 格式為 v<versionName>（如 v1.1），APK 為 release asset。
 */
object UpdateChecker {
    private const val OWNER = "stevedaydream"
    private const val REPO = "AutoSolver"
    private const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    data class ReleaseInfo(
        val versionName: String,   // tag 去掉前綴 v
        val notes: String,         // release body
        val apkUrl: String?,       // 第一個 .apk asset 的下載網址
        val htmlUrl: String        // release 頁面（無 apk asset 時退而開瀏覽器）
    )

    /** 查詢最新 release；網路錯誤或尚無 release 時回傳 null（靜默失敗，不打擾使用者） */
    suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(API_LATEST).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(body)
            val tag = json.getString("tag_name").removePrefix("v")
            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) {
                        apkUrl = a.getString("browser_download_url")
                        break
                    }
                }
            }
            ReleaseInfo(tag, json.optString("body", ""), apkUrl, json.getString("html_url"))
        } catch (e: Exception) {
            null
        }
    }

    /** 逐段比較版本號（1.10 > 1.9） */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    /** 以 DownloadManager 下載 APK，完成後喚起系統安裝流程 */
    fun downloadAndInstall(context: Context, apkUrl: String, versionName: String) {
        val appContext = context.applicationContext
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("AutoDora v$versionName")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, "AutoDora-v$versionName.apk"
            )
            .setMimeType("application/vnd.android.package-archive")
        val downloadId = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
                ctx.unregisterReceiver(this)
                val uri = dm.getUriForDownloadedFile(downloadId) ?: return
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(install)
            }
        }
        // DownloadManager 的完成廣播為系統送出，targetSdk 33+ 需以 RECEIVER_EXPORTED 註冊才收得到
        ContextCompat.registerReceiver(
            appContext, receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }
}
