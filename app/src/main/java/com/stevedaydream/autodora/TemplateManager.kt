package com.stevedaydream.autodora

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

object TemplateManager {
    // 儲存特定屬性的珠子樣板
    // ★ 存入的已是 32x32 裁切後縮圖（由 TemplateCalibrationWindow 處理），直接存 PNG
    fun saveTemplate(context: Context, type: BoardAnalyzer.OrbType, bitmap: Bitmap) {
        if (type == BoardAnalyzer.OrbType.UNKNOWN) return
        val file = File(context.filesDir, "template_${type.name}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    // ★ 修正：移除 targetSize 參數，樣板存入時已是正確尺寸(32x32)，直接讀出即可
    // 不再依賴 orbSize 做二次縮放，避免多次縮放導致的圖像失真
    fun loadTemplates(context: Context): Map<BoardAnalyzer.OrbType, Bitmap> {
        val map = mutableMapOf<BoardAnalyzer.OrbType, Bitmap>()
        BoardAnalyzer.OrbType.values().forEach { type ->
            if (type != BoardAnalyzer.OrbType.UNKNOWN) {
                val file = File(context.filesDir, "template_${type.name}.png")
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        map[type] = bitmap
                    }
                }
            }
        }
        return map
    }
}