

package com.stevedaydream.autodora

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer
import androidx.core.graphics.createBitmap

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    companion object {
        var instance: ScreenCaptureService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "ScreenCaptureChannel")
            .setContentTitle("AutoDora Capture Service")
            .setContentText("Screen capturing is active.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    // --- 修改開頭：修正 resultCode 判斷邏輯與 Intent 取值方式 ---
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 預設值改為 0 (RESULT_CANCELED) 避免與 RESULT_OK (-1) 混淆
        val resultCode = intent?.getIntExtra("RESULT_CODE", 0) ?: 0

        // 針對 Android 13 (API 33) 以上加強 Parcelable 的型別安全取值
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        screenWidth = intent?.getIntExtra("WIDTH", 1080) ?: 1080
        screenHeight = intent?.getIntExtra("HEIGHT", 1920) ?: 1920
        screenDensity = intent?.getIntExtra("DENSITY", 1) ?: 1

        // 修正邏輯：必須是 RESULT_OK (即 -1) 才代表使用者同意授權
        if (resultCode == android.app.Activity.RESULT_OK && data != null && mediaProjection == null) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, data)
            setupVirtualDisplay()
        }

        return START_NOT_STICKY
    }
    // --- 修改結尾 ---

    // --- 修改開頭：新增 MediaProjection.Callback 以符合 Android 14 的安全規範 ---
    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        mediaProjection?.let { projection ->

            // 必須在 createVirtualDisplay 前註冊 Callback
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    // 當使用者從系統介面主動停止分享時的處理
                    virtualDisplay?.release()
                    virtualDisplay = null
                }
            }, null)

            virtualDisplay = projection.createVirtualDisplay(
                "AutoDoraDisplay",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
        }
    }
    // --- 修改結尾 ---

    // --- 修改開頭：增加畫面讀取重試機制，避免畫面靜止時回傳 null ---
    // --- 修改開頭：修復截圖時的記憶體洩漏問題 ---
    // --- 修改開頭：修復 Bitmap 重複參照導致的 Recycle 崩潰陷阱 ---
    fun captureSnapshot(): Bitmap? {
        var image: Image? = imageReader?.acquireLatestImage()

        var retries = 0
        while (image == null && retries < 5) {
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
            image = imageReader?.acquireLatestImage()
            retries++
        }

        if (image == null) return null

        image.use { img ->
            val planes = img.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = createBitmap(screenWidth + rowPadding / pixelStride, screenHeight)
            bitmap.copyPixelsFromBuffer(buffer)

            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)

            // 關鍵修復：檢查如果不是同一個物件，才允許回收舊的！
            if (croppedBitmap !== bitmap) {
                bitmap.recycle()
            }

            return croppedBitmap
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        mediaProjection?.stop()
        mediaProjection = null
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ObsoleteSdkInt")
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ScreenCaptureChannel",
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
