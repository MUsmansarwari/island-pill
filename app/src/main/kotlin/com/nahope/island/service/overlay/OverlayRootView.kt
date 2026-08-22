package com.nahope.island.service.overlay

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Root of the overlay window. Its only job beyond hosting the ComposeView is catching
 * ACTION_OUTSIDE, which the window manager delivers when the user taps anywhere else on screen —
 * that is how the expanded card knows to collapse.
 */
class OverlayRootView(context: Context) : FrameLayout(context) {

    var onOutsideTouch: (() -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            onOutsideTouch?.invoke()
            return true
        }
        return super.onTouchEvent(event)
    }
}
