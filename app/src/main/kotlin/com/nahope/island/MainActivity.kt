package com.nahope.island

import android.Manifest
import android.os.Build
import android.os.SystemClock
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nahope.island.data.IslandConfig
import com.nahope.island.data.IslandPrefs
import com.nahope.island.data.IslandPreview
import com.nahope.island.island.IslandEvent
import com.nahope.island.island.IslandRepository
import com.nahope.island.service.IslandService
import com.nahope.island.ui.theme.IslandTheme
import com.nahope.island.util.CutoutDetector
import com.nahope.island.util.Permissions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IslandTheme {
                SetupScreen()
            }
        }
    }
}

@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { IslandPrefs(context) }
    val config by prefs.config.collectAsState(initial = IslandConfig())

    // Permission state only changes while we are in the background, so re-read it on every resume.
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hasOverlay = remember(resumeTick) { Permissions.canDrawOverlays(context) }
    val hasNotifications = remember(resumeTick) { Permissions.hasNotificationAccess(context) }
    val hasPost = remember(resumeTick) { Permissions.hasPostNotifications(context) }
    val hasBluetooth = remember(resumeTick) { Permissions.hasBluetooth(context) }
    val batteryFree = remember(resumeTick) { Permissions.isBatteryUnrestricted(context) }
    val hasAccessibility = remember(resumeTick) { Permissions.hasAccessibility(context) }

    val requestRuntime = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resumeTick++ }

    val ready = hasOverlay && hasNotifications

    val density = LocalDensity.current.density
    val cutout = remember(resumeTick) { CutoutDetector.detect(context) }
    val halfScreen = remember { context.resources.displayMetrics.widthPixels / 2f }
    // A card can never usefully be wider than the screen, so that is the cap.
    val maxCardWidth = remember {
        context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    text = "Island Pill",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Setup, then calibrate the pill over your punch-hole.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                PermissionRow(
                    title = "Display over other apps",
                    subtitle = "Required. This is the island itself.",
                    granted = hasOverlay,
                ) { context.startActivity(Permissions.overlayIntent(context)) }
            }

            item {
                PermissionRow(
                    title = "Notification access",
                    subtitle = "Required. Feeds media, and unlocks the media-session list.",
                    granted = hasNotifications,
                ) { context.startActivity(Permissions.notificationAccessIntent()) }
            }

            item {
                PermissionRow(
                    title = "Accessibility overlay",
                    subtitle = "Without this the pill renders behind the status bar and stays invisible.",
                    granted = hasAccessibility,
                ) { context.startActivity(Permissions.accessibilityIntent()) }
            }

            item {
                PermissionRow(
                    title = "Post notifications",
                    subtitle = "For the silent keep-alive notification.",
                    granted = hasPost,
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestRuntime.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    }
                }
            }

            item {
                PermissionRow(
                    title = "Nearby devices",
                    subtitle = "Optional. Buds connect + battery badge.",
                    granted = hasBluetooth,
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        requestRuntime.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                    }
                }
            }

            item {
                PermissionRow(
                    title = "Unrestricted battery",
                    subtitle = "Strongly recommended on ColorOS / OxygenOS, which kills overlays.",
                    granted = batteryFree,
                ) { context.startActivity(Permissions.batteryIntent(context)) }
            }

            item {
                Card(colors = CardDefaults.cardColors()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Island enabled", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (ready) "Running as a foreground service" else "Grant the two required permissions first",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = config.enabled,
                            enabled = ready,
                            onCheckedChange = { on ->
                                // The write must land before the service reads it, otherwise the
                                // service sees enabled=false on its first emission and stops itself.
                                scope.launch {
                                    prefs.setEnabled(on)
                                    if (on) IslandService.start(context) else IslandService.stop(context)
                                }
                            },
                        )
                    }
                }
            }

            item {
                Text(
                    "Calibration",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (cutout != null) {
                item {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text("Punch-hole detected", fontWeight = FontWeight.SemiBold)
                            Text(
                                "${cutout.widthPx}×${cutout.heightPx} px, " +
                                    "${-cutout.offsetXPx} px left of centre",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        val holeWidthDp = cutout.widthPx / density
                                        val holeHeightDp = cutout.heightPx / density
                                        prefs.setOffsetX(cutout.offsetXPx)
                                        prefs.setOffsetY(cutout.topPx)
                                        prefs.setIdleWidth(max(holeWidthDp + 40f, 90f))
                                        prefs.setIdleHeight(holeHeightDp)
                                        prefs.setCornerRadius(holeHeightDp / 2f)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Auto-align pill over the hole")
                            }
                        }
                    }
                }
            }

            item {
                SliderRow(
                    title = "Horizontal offset",
                    value = config.offsetX.toFloat(),
                    min = -halfScreen,
                    max = halfScreen,
                    unit = "px",
                    preview = { config.copy(offsetX = it.roundToInt()) },
                ) { scope.launch { prefs.setOffsetX(it.roundToInt()) } }
            }
            item {
                SliderRow(
                    title = "Vertical offset",
                    value = config.offsetY.toFloat(),
                    min = 0f,
                    max = 160f,
                    unit = "px",
                    preview = { config.copy(offsetY = it.roundToInt()) },
                ) { scope.launch { prefs.setOffsetY(it.roundToInt()) } }
            }
            item {
                SliderRow(
                    title = "Idle width",
                    value = config.idleWidth,
                    min = 60f,
                    max = 280f,
                    unit = "dp",
                    preview = { config.copy(idleWidth = it) },
                ) { scope.launch { prefs.setIdleWidth(it) } }
            }
            item {
                SliderRow(
                    title = "Idle height",
                    value = config.idleHeight,
                    min = 18f,
                    max = 64f,
                    unit = "dp",
                    preview = { config.copy(idleHeight = it) },
                ) { scope.launch { prefs.setIdleHeight(it) } }
            }
            item {
                SliderRow(
                    title = "Corner radius",
                    value = config.cornerRadius,
                    min = 0f,
                    max = 40f,
                    unit = "dp",
                    preview = { config.copy(cornerRadius = it) },
                ) { scope.launch { prefs.setCornerRadius(it) } }
            }
            item {
                SliderRow(
                    title = "Expanded width",
                    value = config.expandedWidth,
                    min = 240f,
                    max = maxCardWidth,
                    unit = "dp",
                    preview = { config.copy(expandedWidth = it) },
                    expandWhileEditing = true,
                ) { scope.launch { prefs.setExpandedWidth(it) } }
            }

            item {
                SliderRow(
                    title = "Expanded height",
                    value = config.expandedHeight,
                    min = 120f,
                    max = 320f,
                    unit = "dp",
                    preview = { config.copy(expandedHeight = it) },
                    expandWhileEditing = true,
                ) { scope.launch { prefs.setExpandedHeight(it) } }
            }

            item {
                ToggleRow("Show pill when idle", config.showWhenIdle) {
                    scope.launch { prefs.setShowWhenIdle(it) }
                }
            }
            item {
                ToggleRow("Hide in landscape", config.hideOnLandscape) {
                    scope.launch { prefs.setHideOnLandscape(it) }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = {
                        IslandRepository.publish(
                            IslandEvent.Bluetooth(
                                deviceName = "Calibration",
                                connected = true,
                                batteryPercent = 87,
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Fire a test event")
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    onFix: () -> Unit,
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (granted) {
                Text("Granted", color = MaterialTheme.colorScheme.primary)
            } else {
                Button(onClick = onFix) { Text("Grant") }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    min: Float,
    max: Float,
    unit: String,
    preview: (Float) -> IslandConfig,
    expandWhileEditing: Boolean = false,
    onCommit: (Float) -> Unit,
) {
    // Dragging must not write to DataStore on every frame, so the slider is locally owned, drives
    // an in-memory preview while the finger is down, and only commits when it lifts.
    var local by remember { mutableFloatStateOf(value) }
    var openedForPreview by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(value) { local = value }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${local.roundToInt()} $unit",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = local.coerceIn(min, max),
            onValueChange = {
                local = it
                IslandPreview.show(preview(it))
                // Sizing the card is impossible while it is shut, so open it on first touch.
                if (expandWhileEditing && !openedForPreview) {
                    openedForPreview = openPreviewCard()
                }
            },
            onValueChangeFinished = {
                onCommit(local)
                IslandPreview.clear()
                if (openedForPreview) {
                    openedForPreview = false
                    // Leave it up briefly so the committed size is actually visible.
                    scope.launch {
                        delay(2_500)
                        IslandRepository.clear(IslandEvent.Media.ID)
                    }
                }
            },
            valueRange = min..max,
        )
    }
}

/**
 * Opens the expanded card for calibration. Returns true only if it had to invent an event, so the
 * caller knows whether to clean up afterwards — a real track already playing must be left alone.
 */
private fun openPreviewCard(): Boolean {
    val invented = IslandRepository.state.value.primary == null
    if (invented) {
        IslandRepository.publish(
            IslandEvent.Media(
                packageName = "com.nahope.island",
                appName = "Island Pill",
                title = "Preview track",
                artist = "Calibration",
                artwork = null,
                isPlaying = true,
                positionMs = 42_000,
                positionSampledAt = SystemClock.elapsedRealtime(),
                durationMs = 120_000,
                accent = null,
            )
        )
    }
    IslandRepository.setExpanded(true)
    return invented
}
