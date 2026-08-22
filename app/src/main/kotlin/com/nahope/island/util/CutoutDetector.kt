package com.nahope.island.util

import android.content.Context
import android.os.Build
import android.view.WindowManager

/**
 * Where the physical camera hole actually is, in raw pixels.
 *
 * [offsetXPx] is measured from the centre of the screen because the overlay window is laid out with
 * CENTER_HORIZONTAL gravity — so this value drops straight into WindowManager.LayoutParams.x.
 * Negative means the hole sits left of centre, which is the case on most Realme/OnePlus panels.
 */
data class CutoutInfo(
    val offsetXPx: Int,
    val topPx: Int,
    val widthPx: Int,
    val heightPx: Int,
)

object CutoutDetector {

    /**
     * Must be called from an Activity context — a Service's context is not a display context and
     * currentWindowMetrics would report the wrong bounds.
     */
    fun detect(context: Context): CutoutInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val wm = context.getSystemService(WindowManager::class.java) ?: return null
        val metrics = wm.currentWindowMetrics
        val cutout = metrics.windowInsets.displayCutout ?: return null

        val rect = cutout.boundingRectTop
        if (rect.isEmpty) return null

        return CutoutInfo(
            offsetXPx = rect.centerX() - metrics.bounds.width() / 2,
            topPx = rect.top,
            widthPx = rect.width(),
            heightPx = rect.height(),
        )
    }
}
