package com.stevedaydream.autodora

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.animation.LinearInterpolator
import android.view.WindowManager

/**
 * 全螢幕透明路徑層（TYPE_ACCESSIBILITY_OVERLAY + FLAG_NOT_TOUCHABLE，不攔截遊戲觸控）。
 * 只建立一個 [PathRenderView]：切換候選時更新 model 並 invalidate，不反覆 remove/add 視窗。
 */
class PathOverlayWindow(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var renderView: PathRenderView? = null
    private var animator: ValueAnimator? = null
    private var currentModel: PathRenderModel? = null

    val isShowing: Boolean get() = renderView?.isAttachedToWindow == true

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            // 視窗原點對齊螢幕 (0,0)，讓繪製座標與截圖像素、手勢座標一致
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        // Android 11+ 以 inset 決定避讓；0 = 不避讓狀態列/導覽列，並延伸進瀏海區
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private fun ensureView(): PathRenderView {
        renderView?.let { if (it.isAttachedToWindow) return it }
        val view = PathRenderView(context)
        windowManager.addView(view, params())
        renderView = view
        return view
    }

    /** 顯示（或就地更新）一條候選路線。 */
    fun show(model: PathRenderModel, style: PathRenderStyle) {
        stopAnimation()
        currentModel = model
        val view = ensureView()
        view.setModel(model, style)
        view.setProgress(1f)
    }

    /** 純視覺預演：只推進畫面進度，絕不送出手勢。 */
    fun play(durationMs: Long, fromProgress: Float, onProgress: (Float) -> Unit, onEnd: () -> Unit) {
        val view = renderView ?: return
        stopAnimation()
        animator = ValueAnimator.ofFloat(fromProgress.coerceIn(0f, 1f), 1f).apply {
            duration = (durationMs * (1f - fromProgress.coerceIn(0f, 1f))).toLong().coerceAtLeast(1L)
            interpolator = LinearInterpolator()
            addUpdateListener {
                val p = it.animatedValue as Float
                view.setProgress(p)
                onProgress(p)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { canceled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!canceled) onEnd() }
            })
            start()
        }
    }

    fun setProgress(progress: Float) { renderView?.setProgress(progress) }

    fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    fun hide() {
        stopAnimation()
        currentModel = null
        renderView?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        renderView = null
    }
}
