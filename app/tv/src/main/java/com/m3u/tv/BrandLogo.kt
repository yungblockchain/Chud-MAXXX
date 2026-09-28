package com.m3u.tv

import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One full turn every nine seconds: slow enough to read as a sign, not a loading spinner. */
private const val SECONDS_PER_TURN = 9f

/**
 * The CHUD STREAMS badge turning slowly around its vertical axis, like a coin on a neon sign,
 * with a steady cyan glow behind it that doesn't turn.
 *
 * - Only the draw layer changes each frame (no recomposition), so it costs the Firestick very little.
 * - [spinning] is false while something covers it (the player, a details page, the launch
 *   screen); the badge then holds its angle and carries on from there when it's visible again.
 * - If the device's "Remove animations" accessibility setting is on, the badge stays still.
 */
@Composable
fun SpinningBrandLogo(
    spinning: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
) {
    val context = LocalContext.current
    val motionAllowed = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f) > 0f
    }
    var angle by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(spinning, motionAllowed) {
        if (!spinning || !motionAllowed) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val seconds = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
                last = now
                angle = (angle + seconds * 360f / SECONDS_PER_TURN) % 360f
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(TvColors.Focus.copy(alpha = 0.32f), Color.Transparent),
                        center = center,
                        radius = this.size.minDimension * 0.62f,
                    ),
                    radius = this.size.minDimension * 0.62f,
                )
            }
    ) {
        Image(
            painter = painterResource(R.drawable.brand_mascot),
            // Decorative: the launcher already announces the app name.
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Read inside the layer block so each frame only redraws, never recomposes.
                    rotationY = angle
                    // A long camera distance gives real perspective instead of a flat squash.
                    cameraDistance = 12f * density
                }
        )
    }
}
