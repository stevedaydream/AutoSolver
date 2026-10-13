package com.stevedaydream.autodora

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** 快速設定磚：遊戲中下拉即可開關懸浮視窗；無障礙服務未啟用時導向設定頁 */
class FloatingWindowTileService : TileService() {

    companion object {
        fun requestRefresh(context: Context) {
            runCatching {
                requestListeningState(context, ComponentName(context, FloatingWindowTileService::class.java))
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val service = AutoPuzzlerAccessibilityService.instance
        if (service == null) {
            openAccessibilitySettings()
            return
        }
        if (service.isFloatingWindowShown) service.hideFloatingWindow() else service.showFloatingWindow()
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val service = AutoPuzzlerAccessibilityService.instance
        tile.state = when {
            service == null -> Tile.STATE_INACTIVE
            service.isFloatingWindowShown -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                service == null -> "需啟用無障礙"
                service.isFloatingWindowShown -> "顯示中"
                else -> "已關閉"
            }
        }
        tile.updateTile()
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
