package io.github.aedev.flow.ui.components.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/** Tracks whether the opening dive has played during this process lifetime. */
object OpenTubeOpening {
    var played = false
}

private const val MARK_VIEWPORT = 108f

// OpenTube brand mark in 108x108 coordinate space
private const val OPENTUBE_RING_PATH =
    "M54,18 A36,36 0 1,0 54,90 A36,36 0 1,0 54,18 Z M54,26.5 A27.5,27.5 0 1,1 54,81.5 A27.5,27.5 0 1,1 54,26.5 Z"
private const val OPENTUBE_PLAY_PATH =
    "M49,41 L66.5,52.2 C67.8,53.0 67.8,55.0 66.5,55.8 L49,67.0 C47.6,67.9 46,66.9 46,65.2 L46,42.8 C46,41.1 47.6,40.1 49,41 Z"
private const val OPENTUBE_MASK_PATH =
    "M54,18 A36,36 0 1,0 54,90 A36,36 0 1,0 54,18 Z"

private val OPENING_FIELD = Color.Black

/**
 * OpenAnimation: The mark takes over from the system splash at 240dp, settles with a light gleam,
 * and then dives forward into the app UI with exponential expansion and hardware DstOut punching.
 */
@Composable
fun OpenTubeOpeningReveal(
    onDive: (Float) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ringPath = remember { PathParser().parsePathString(OPENTUBE_RING_PATH).toPath().apply { fillType = PathFillType.EvenOdd } }
    val playPath = remember { PathParser().parsePathString(OPENTUBE_PLAY_PATH).toPath() }
    val maskPath = remember { PathParser().parsePathString(OPENTUBE_MASK_PATH).toPath() }
    val brandColor = MaterialTheme.colorScheme.primary

    val settle = remember { Animatable(0f) }
    val dive = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    val report by rememberUpdatedState(onDive)

    LaunchedEffect(Unit) {
        settle.animateTo(1f, tween(SETTLE_MS, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
        delay(HOLD_MS)
        dive.animateTo(1f, tween(DIVE_MS, easing = CubicBezierEasing(0.6f, 0f, 0.85f, 0.35f))) {
            report(value)
        }
        report(1f)
        OpenTubeOpening.played = true
        done()
    }

    Canvas(
        modifier =
            modifier
                .fillMaxSize()
                .graphicsLayer {
                    val d = dive.value
                    compositingStrategy =
                        if (d > 0.03f && d < 0.98f) {
                            CompositingStrategy.Offscreen
                        } else {
                            CompositingStrategy.Auto
                        }
                },
    ) {
        val d = dive.value
        val st = settle.value
        val base = 240.dp.toPx() / MARK_VIEWPORT
        val breathe = 1f + 0.04f * sin(Math.PI.toFloat() * st)
        val s = base * breathe * exp(ln(ZOOM) * d * d)
        val window = smooth(0.04f, 0.28f, d)
        val fade = 1f - smooth(0.82f, 1f, d)
        val maxDim = max(size.width, size.height)
        val markScreenSize = s * MARK_VIEWPORT

        if (fade > 0f) {
            drawRect(OPENING_FIELD, alpha = fade)
        }

        val rim = Stroke(width = RIM.toPx() / s, join = StrokeJoin.Round)

        translate(center.x - MARK_VIEWPORT / 2f * s, center.y - MARK_VIEWPORT / 2f * s) {
            scale(s, s, pivot = Offset.Zero) {
                // Initial logo rendering: Dynamic theme brand ring + white play triangle
                if (window < 1f) {
                    val alpha = (1f - window).coerceIn(0f, 1f)
                    drawPath(ringPath, brandColor, alpha = alpha)
                    drawPath(playPath, Color.White, alpha = alpha)
                }

                // Specular sweep band across the mark while settling
                if (st in 0.05f..0.98f && d == 0f) {
                    val x = -20f + 150f * st
                    val sweepBrush =
                        Brush.linearGradient(
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.85f),
                                Color.Transparent,
                            ),
                            start = Offset(x - 16f, 20f),
                            end = Offset(x + 16f, 88f),
                        )
                    drawPath(ringPath, sweepBrush, blendMode = BlendMode.SrcAtop)
                    drawPath(playPath, sweepBrush, blendMode = BlendMode.SrcAtop)
                }

                // Window punch through into the app
                if (window > 0f && markScreenSize < maxDim * 2.8f) {
                    drawPath(maskPath, Color.Black, alpha = window, blendMode = BlendMode.DstOut)
                }

                // Outline rim highlighting the expanding portal
                if (fade > 0f && markScreenSize < maxDim * 2.5f) {
                    drawPath(maskPath, Color.White, alpha = fade * 0.9f, style = rim)
                }
            }
        }
    }
}

private fun smooth(
    from: Float,
    to: Float,
    x: Float,
): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private val RIM = 2.5.dp
private const val SETTLE_MS = 480
private const val HOLD_MS = 60L
private const val DIVE_MS = 650
private const val ZOOM = 85f
