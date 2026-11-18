package com.stevedaydream.autodora

import android.content.Context
import android.content.SharedPreferences
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * 解盤／辨識共用的盤面位置：有校正值就用校正值，沒有就依整個螢幕尺寸估計（[TeamLayout.estimateBoard]）。
 * 舊版直接用 startX=0、startY=1000、orbSize=150 當預設，未校正的手機格子會整片偏上、偏小。
 */
object BoardCalibration {
    data class Values(val startX: Int, val startY: Int, val orbSize: Int, val calibrated: Boolean)

    fun realScreenSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }
        return metrics.widthPixels to metrics.heightPixels
    }

    /** [screenWidth]／[screenHeight] 應與截圖同一座標系；有截圖時傳截圖尺寸。 */
    fun resolve(prefs: SharedPreferences, screenWidth: Int, screenHeight: Int): Values {
        val saved = if (prefs.contains("orbSize")) Triple(prefs.getInt("startX", 0), prefs.getInt("startY", 0), prefs.getInt("orbSize", 0))
            .takeIf { it.third > 0 } else null
        val board = TeamLayout.board(saved, screenWidth.toFloat(), screenHeight.toFloat())
        return Values(board.x.toInt(), board.y.toInt(), board.orb.toInt(), board.calibrated)
    }

    fun resolve(context: Context, prefs: SharedPreferences): Values {
        val (w, h) = realScreenSize(context)
        return resolve(prefs, w, h)
    }
}
