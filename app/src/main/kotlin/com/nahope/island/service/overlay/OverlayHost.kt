package com.nahope.island.service.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nahope.island.data.IslandConfig
import com.nahope.island.data.IslandPreview
import com.nahope.island.island.IslandMode
import com.nahope.island.island.IslandRepository
import com.nahope.island.ui.island.IslandOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The overlay window itself, independent of who hosts it.
 *
 * Two hosts exist because the window type decides everything:
 *  - TYPE_APPLICATION_OVERLAY (layer 111000) draws *under* the status bar on Android 12+, so the
 *    pill is invisible in the one place it needs to be.
 *  - TYPE_ACCESSIBILITY_OVERLAY sits above it, but only an AccessibilityService may add one.
 *
 * The window goes full width the moment anything lands on the island, and shrinks back to the pill
 * when it empties. That split matters: a WRAP_CONTENT window derives its measure constraint from
 * its own current frame, so once anything clamps it narrow — a mid-animation reposition, say — it
 * can never grow back and the card silently stays whatever width it managed at that instant. Full
 * width removes the ceiling and lets Compose own horizontal placement, which is what lets the pill
 * glide to centre as it expands. Idle, wrapping tight again keeps a full-width strip from
 * swallowing the status-bar swipe.
 */
class OverlayHost(
    private val context: Context,
    private val windowType: Int,
    private val config: StateFlow<IslandConfig>,
    private val landscape: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val onOpenApp: () -> Unit,
) {

    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)

    private var root: OverlayRootView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var params: WindowManager.LayoutParams? = null
    private var geometryJob: Job? = null
    private var widthJob: Job? = null

    /**
     * Full width only while something is on the island. Idle, the window shrinks back to the pill
     * so a full-width strip across the status bar never swallows the swipe-down gesture.
     */
    private val fullWidth = MutableStateFlow(false)

    val isAttached: Boolean get() = root != null

    fun attach(): Boolean {
        if (root != null) return true
        val wm = windowManager ?: return false

        val lifecycleOwner = OverlayLifecycleOwner().also { it.onCreate() }

        // These must go on the window's ROOT view, not on the ComposeView: Compose resolves the
        // recomposer from view.rootView, so owners set on a child are never found.
        val view = OverlayRootView(context).apply {
            onOutsideTouch = { IslandRepository.setExpanded(false) }
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        }

        view.addView(
            ComposeView(context).apply {
                setContent {
                    val stored by config.collectAsState()
                    val preview by IslandPreview.override.collectAsState()
                    val state by IslandRepository.state.collectAsState()
                    val isLandscape by landscape.collectAsState()
                    val isFullWidth by fullWidth.collectAsState()
                    val effective = preview ?: stored

                    IslandOverlay(
                        state = state,
                        config = effective,
                        hidden = isLandscape && effective.hideOnLandscape,
                        onTap = { IslandRepository.toggleExpanded() },
                        onLongPress = onOpenApp,
                        fullWidth = isFullWidth,
                    )
                }
            }
        )

        val lp = buildLayoutParams(config.value)
        val added = runCatching { wm.addView(view, lp) }
            .onFailure { Log.e(TAG, "addView failed for type $windowType", it) }
            .isSuccess

        if (!added) {
            lifecycleOwner.onDestroy()
            return false
        }

        root = view
        owner = lifecycleOwner
        params = lp

        geometryJob = scope.launch {
            combine(config, IslandPreview.override) { stored, preview -> preview ?: stored }
                .collect(::applyVerticalOffset)
        }

        widthJob = scope.launch {
            IslandRepository.state
                .map { it.mode != IslandMode.IDLE && it.mode != IslandMode.HIDDEN }
                .distinctUntilChanged()
                .collect(::applyWindowWidth)
        }

        Log.i(TAG, "overlay attached type=$windowType y=${lp.y}")
        return true
    }

    fun detach() {
        geometryJob?.cancel()
        geometryJob = null
        widthJob?.cancel()
        widthJob = null
        fullWidth.value = false

        root?.let { view -> runCatching { windowManager?.removeView(view) } }
        owner?.onDestroy()
        root = null
        owner = null
        params = null
    }

    private fun applyWindowWidth(hasContent: Boolean) {
        val lp = params ?: return
        val view = root ?: return
        if (fullWidth.value == hasContent) return

        fullWidth.value = hasContent
        if (hasContent) {
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.x = 0
        } else {
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT
            lp.x = restingLeft(currentConfig())
        }
        runCatching { windowManager?.updateViewLayout(view, lp) }
    }

    private fun currentConfig(): IslandConfig = IslandPreview.override.value ?: config.value

    /** Left edge of the idle pill, clamped so it never hangs off a screen edge. */
    private fun restingLeft(cfg: IslandConfig): Int {
        val metrics = context.resources.displayMetrics
        val idleWidth = (cfg.idleWidth * metrics.density).toInt()
        val holeCentre = metrics.widthPixels / 2 + cfg.offsetX
        return (holeCentre - idleWidth / 2)
            .coerceIn(0, (metrics.widthPixels - idleWidth).coerceAtLeast(0))
    }

    /** Horizontal placement lives in Compose now; only the vertical nudge is a window property. */
    private fun applyVerticalOffset(cfg: IslandConfig) {
        val lp = params ?: return
        val view = root ?: return
        if (lp.y == cfg.offsetY) return
        lp.y = cfg.offsetY
        runCatching { windowManager?.updateViewLayout(view, lp) }
    }

    private fun buildLayoutParams(cfg: IslandConfig) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = restingLeft(cfg)
        y = cfg.offsetY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private companion object {
        const val TAG = "OverlayHost"
    }
}
