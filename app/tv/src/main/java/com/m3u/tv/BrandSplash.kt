package com.m3u.tv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * Launch screen: the logo badge turns slowly around its vertical axis like a coin (two
 * turns, easing to a stop face-on), then the screen fades into the app. About three and a half
 * seconds in all; it can be switched off under Settings > Player and startup.
 */
@Composable
fun BrandSplash(onFinished: () -> Unit) {
    val spin = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val currentOnFinished by rememberUpdatedState(onFinished)
    val appName = stringResource(R.string.app_name)

    LaunchedEffect(Unit) {
        spin.animateTo(
            targetValue = 720f,
            animationSpec = tween(durationMillis = 3_000, easing = FastOutSlowInEasing),
        )
        fade.animateTo(0f, animationSpec = tween(durationMillis = 400))
        currentOnFinished()
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(TvColors.Background)
            .semantics { contentDescription = appName }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.brand_mascot),
                contentDescription = null,
                modifier = Modifier
                    .size(220.dp)
                    .graphicsLayer {
                        rotationY = spin.value
                        // A longer camera distance keeps the turn looking like depth, not a squash.
                        cameraDistance = 14f * density
                    }
            )
            // Wordmark with a magenta ghost offset behind it, like a misregistered anime title card.
            Box {
                Text(
                    text = appName,
                    color = TvColors.Accent,
                    fontFamily = TvFonts.Accent,
                    fontSize = 44.sp,
                    maxLines = 1,
                    modifier = Modifier.offset(x = 3.dp, y = 2.dp)
                )
                Text(
                    text = appName,
                    color = TvColors.Focus,
                    fontFamily = TvFonts.Accent,
                    fontSize = 44.sp,
                    maxLines = 1,
                )
            }
            Text(
                text = stringResource(R.string.dial_brand_katakana),
                color = TvColors.Accent,
                fontSize = 20.sp,
                letterSpacing = 6.sp,
                maxLines = 1,
            )
        }
        // CRT scanlines over the whole screen.
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val spacing = 3.dp.toPx()
                    val line = 1.dp.toPx()
                    onDrawBehind {
                        var y = 0f
                        while (y < size.height) {
                            drawRect(Color.Black.copy(alpha = 0.22f), Offset(0f, y), Size(size.width, line))
                            y += spacing
                        }
                    }
                }
        )
    }
}
