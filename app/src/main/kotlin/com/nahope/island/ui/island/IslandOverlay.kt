package com.nahope.island.ui.island

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.nahope.island.data.IslandConfig
import com.nahope.island.island.IslandEvent
import com.nahope.island.island.IslandMode
import com.nahope.island.island.IslandUiState
import com.nahope.island.island.sources.MediaCommands
import kotlinx.coroutines.delay
import kotlin.math.max

private val IslandBlack = Color(0xFF000000)
private val IslandWhite = Color(0xFFF2F2F7)
private val IslandDim = Color(0xFF9A9AA0)
private val ChargeGreen = Color(0xFF32D74B)

/** Must match the MINIMAL-state growth used below. */
private const val MINIMAL_GROWTH = 86f

/**
 * The spring that sells the whole illusion. Slightly under-damped so the pill overshoots a hair on
 * every size change — that tiny bounce is what reads as "gummy" instead of "a box that resized".
 */
private val MorphSpring = spring<Dp>(
    dampingRatio = 0.72f,
    stiffness = Spring.StiffnessMediumLow,
)

@Composable
fun IslandOverlay(
    state: IslandUiState,
    config: IslandConfig,
    hidden: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    fullWidth: Boolean,
) {
    val idle = state.mode == IslandMode.IDLE
    val invisible = hidden || (idle && !config.showWhenIdle)

    if (invisible) {
        // A 1dp stub keeps the window alive without painting anything.
        Spacer(Modifier.size(1.dp))
        return
    }

    val height: Dp = when (state.mode) {
        IslandMode.EXPANDED -> expandedHeightFor(state.primary, config)
        IslandMode.IDLE -> config.idleHeight.dp
        else -> max(config.idleHeight, 36f).dp
    }
    val width: Dp = when (state.mode) {
        IslandMode.EXPANDED -> config.expandedWidth.dp
        IslandMode.IDLE -> config.idleWidth.dp
        IslandMode.MINIMAL -> (config.idleWidth + MINIMAL_GROWTH).dp
        IslandMode.COMPACT -> (config.idleWidth + 52f).dp
        IslandMode.HIDDEN -> 1.dp
    }
    val radius: Dp = when (state.mode) {
        IslandMode.EXPANDED -> 34.dp
        else -> config.cornerRadius.dp
    }

    val animatedWidth by animateDpAsState(width, MorphSpring, label = "island-width")
    val animatedHeight by animateDpAsState(height, MorphSpring, label = "island-height")
    val animatedRadius by animateDpAsState(radius, MorphSpring, label = "island-radius")

    // Collapsed, the pill sits over the punch-hole; expanded, the card centres on the screen the
    // way iOS does. Animating the difference here rather than moving the window means the slide
    // and the morph are driven by the same spring and can never disagree.
    val screenWidth = LocalConfiguration.current.screenWidthDp.toFloat()
    val holeCentre = screenWidth / 2f + with(LocalDensity.current) { config.offsetX.toDp().value }
    val widestSmall = config.idleWidth + MINIMAL_GROWTH
    val restingCentre = holeCentre.coerceIn(
        widestSmall / 2f,
        (screenWidth - widestSmall / 2f).coerceAtLeast(widestSmall / 2f),
    )
    val offsetTarget = when {
        // Narrow window: it is already parked over the hole, so the content must not shift inside it.
        !fullWidth -> 0.dp
        state.mode == IslandMode.EXPANDED -> 0.dp
        else -> (restingCentre - screenWidth / 2f).dp
    }
    // Re-created whenever the window swaps width mode, which snaps rather than animates — the
    // window itself moved by exactly the same amount, so the pill stays put on screen.
    val offset = remember(fullWidth) { Animatable(offsetTarget, Dp.VectorConverter) }
    LaunchedEffect(offsetTarget) { offset.animateTo(offsetTarget, MorphSpring) }

    Box(
        modifier = if (fullWidth) Modifier.fillMaxWidth() else Modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
    Row(
        modifier = Modifier.offset(x = offset.value),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(animatedWidth)
                .height(animatedHeight)
                .clip(RoundedCornerShape(animatedRadius))
                .background(IslandBlack)
                .pointerInput(state.mode) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onLongPress = { onLongPress() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = state.mode to state.primary?.id,
                transitionSpec = {
                    (fadeIn(tween(180, delayMillis = 60)) + scaleIn(tween(220), initialScale = 0.92f))
                        .togetherWith(fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 0.94f))
                },
                label = "island-content",
            ) { (mode, _) ->
                when (mode) {
                    IslandMode.IDLE, IslandMode.HIDDEN -> Box(Modifier.fillMaxSize())
                    IslandMode.EXPANDED -> ExpandedContent(state.primary)
                    else -> CompactContent(state.primary)
                }
            }
        }

        if (state.mode == IslandMode.COMPACT && state.secondary != null) {
            Box(
                modifier = Modifier
                    .size(animatedHeight)
                    .clip(CircleShape)
                    .background(IslandBlack),
                contentAlignment = Alignment.Center,
            ) {
                SecondaryGlyph(state.secondary)
            }
        }
    }
    }
}

/**
 * Media needs room for art, two lines of text, a scrubber and three buttons; a charging or silent
 * banner is a single row, so it is capped rather than stretched to match.
 */
private fun expandedHeightFor(event: IslandEvent?, config: IslandConfig): Dp = when (event) {
    is IslandEvent.Media -> config.expandedHeight.dp
    else -> config.expandedHeight.coerceAtMost(118f).dp
}

// region compact

@Composable
private fun CompactContent(event: IslandEvent?) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        when (event) {
            is IslandEvent.Media -> {
                Artwork(event, size = 22.dp)
                Waveform(playing = event.isPlaying)
            }

            is IslandEvent.Charging -> {
                Glyph(Icons.Rounded.Bolt, ChargeGreen)
                Label("${event.levelPercent}%")
            }

            is IslandEvent.Ringer -> {
                Glyph(
                    when (event.mode) {
                        IslandEvent.Ringer.Mode.SILENT -> Icons.Rounded.NotificationsOff
                        IslandEvent.Ringer.Mode.VIBRATE -> Icons.Rounded.Vibration
                        IslandEvent.Ringer.Mode.NORMAL -> Icons.Rounded.Notifications
                    },
                    IslandWhite,
                )
                Label(
                    when (event.mode) {
                        IslandEvent.Ringer.Mode.SILENT -> "Silent"
                        IslandEvent.Ringer.Mode.VIBRATE -> "Vibrate"
                        IslandEvent.Ringer.Mode.NORMAL -> "Ring"
                    }
                )
            }

            is IslandEvent.Bluetooth -> {
                Glyph(Icons.Rounded.Headphones, IslandWhite)
                Label(event.batteryPercent?.let { "$it%" } ?: event.deviceName)
            }

            is IslandEvent.Notification -> {
                AppIcon(event, size = 22.dp)
                Label(event.title ?: event.appName)
            }

            null -> Unit
        }
    }
}

@Composable
private fun SecondaryGlyph(event: IslandEvent) {
    when (event) {
        is IslandEvent.Media -> Artwork(event, size = 20.dp)
        is IslandEvent.Charging -> Glyph(Icons.Rounded.Bolt, ChargeGreen)
        is IslandEvent.Bluetooth -> Glyph(Icons.Rounded.Headphones, IslandWhite)
        is IslandEvent.Notification -> AppIcon(event, size = 20.dp)
        else -> Glyph(Icons.Rounded.Notifications, IslandWhite)
    }
}

// endregion

// region expanded

@Composable
private fun ExpandedContent(event: IslandEvent?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (event) {
            is IslandEvent.Media -> ExpandedMedia(event)
            is IslandEvent.Notification -> ExpandedNotification(event)
            is IslandEvent.Bluetooth -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(Icons.Rounded.Headphones, IslandWhite)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Label(event.deviceName, size = 15)
                        event.batteryPercent?.let { Label("$it% battery", size = 12, dim = true) }
                    }
                }
            }

            is IslandEvent.Charging -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(Icons.Rounded.Bolt, ChargeGreen)
                    Spacer(Modifier.width(12.dp))
                    Label("${event.levelPercent}% — ${if (event.fast) "Fast charging" else "Charging"}", size = 15)
                }
            }

            else -> Unit
        }
    }
}

@Composable
private fun ExpandedMedia(media: IslandEvent.Media) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Artwork(media, size = 52.dp, radius = 12.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Label(media.title, size = 15, weight = FontWeight.SemiBold)
            // When the app published no metadata the title already *is* the app name; repeating it
            // underneath just looks like a bug.
            val subtitle = media.artist ?: media.appName.takeIf { it != media.title }
            subtitle?.let { Label(it, size = 12, dim = true) }
        }
    }

    ProgressTrack(media)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(Icons.Rounded.SkipPrevious, 30.dp) { MediaCommands.previous() }
        TransportButton(
            if (media.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            38.dp,
        ) { MediaCommands.playPause() }
        TransportButton(Icons.Rounded.SkipNext, 30.dp) { MediaCommands.next() }
    }
}

@Composable
private fun ProgressTrack(media: IslandEvent.Media) {
    var now by remember(media.positionSampledAt) {
        mutableLongStateOf(SystemClock.elapsedRealtime())
    }
    LaunchedEffect(media.isPlaying, media.positionSampledAt) {
        while (media.isPlaying) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }

    val elapsed = if (media.isPlaying) now - media.positionSampledAt else 0L
    val position = (media.positionMs + elapsed).coerceAtLeast(0L)
    val fraction = if (media.durationMs > 0) {
        (position.toFloat() / media.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(IslandWhite.copy(alpha = 0.22f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .clip(CircleShape)
                .background(IslandWhite),
        )
    }
}

@Composable
private fun ExpandedNotification(event: IslandEvent.Notification) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(event, size = 34.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Label(event.title ?: event.appName, size = 15, weight = FontWeight.SemiBold)
            event.text?.let { Label(it, size = 12, dim = true) }
        }
    }
}

@Composable
private fun TransportButton(icon: ImageVector, size: Dp, onClick: () -> Unit) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = IslandWhite,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
    )
}

// endregion

// region pieces

@Composable
private fun Artwork(media: IslandEvent.Media, size: Dp, radius: Dp = 6.dp) {
    val art = media.artwork
    if (art != null) {
        androidx.compose.foundation.Image(
            bitmap = art.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(radius)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(radius))
                .background(IslandWhite.copy(alpha = 0.15f)),
        )
    }
}

@Composable
private fun AppIcon(event: IslandEvent.Notification, size: Dp) {
    val drawable = event.icon
    if (drawable != null) {
        val bitmap = remember(event.id) { drawable.toBitmap().asImageBitmap() }
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        Glyph(Icons.Rounded.Notifications, IslandWhite)
    }
}

@Composable
private fun Glyph(icon: ImageVector, tint: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
private fun Label(
    text: String,
    size: Int = 13,
    dim: Boolean = false,
    weight: FontWeight = FontWeight.Medium,
) {
    Text(
        text = text,
        color = if (dim) IslandDim else IslandWhite,
        fontSize = size.sp,
        fontWeight = weight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Three bars that breathe with the beat. Purely decorative — no audio session is read. */
@Composable
private fun Waveform(playing: Boolean) {
    val transition = rememberInfiniteTransition(label = "waveform")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        listOf(0, 140, 280).forEach { offset ->
            val scale by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 560, delayMillis = offset),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                ),
                label = "bar-$offset",
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(16.dp)
                    .graphicsLayer {
                        scaleY = if (playing) scale else 0.3f
                    }
                    .clip(CircleShape)
                    .background(ChargeGreen),
            )
        }
    }
}

// endregion
