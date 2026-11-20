package io.github.aedev.flow.ui.components.shared

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.local.GlassStyle

/** Whether hardware runtime shaders and backdrop lens refraction are supported. */
val liquidGlassSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** Whether recorded layer blur via RenderEffect is supported (Android 12+). */
val backdropBlurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Hairline specular highlight along the top-left to bottom-right edge, simulating physical light
 * incidence on the glass bevel.
 */
@Composable
fun Modifier.dockHighlight(shape: Shape = CircleShape): Modifier {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val light = Color.White.copy(alpha = if (dark) 0.45f else 0.85f)
    val mid = Color.White.copy(alpha = if (dark) 0.12f else 0.25f)
    val bottom = Color.White.copy(alpha = if (dark) 0.32f else 0.60f)
    return this.border(
        width = 1.dp,
        brush =
            Brush.linearGradient(
                0f to light,
                0.45f to mid,
                1f to bottom,
            ),
        shape = shape,
    )
}

/**
 * Soft drop shadow giving floating glass surfaces physical elevation above scrolling content.
 */
fun Modifier.dockShadow(shape: Shape = CircleShape): Modifier =
    this.shadow(
        elevation = 16.dp,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = 0.35f),
        spotColor = Color.Black.copy(alpha = 0.45f),
    )

/**
 * Three-tier progressive glass surface:
 * 1. Liquid Glass: translucent optical density with specular bevel and refraction depth.
 * 2. Frosted Glass: soft satiny diffusion substrate.
 * 3. Solid M3 Surface (reduceBlur): Material 3 tonal elevation with specular rim.
 */
@Composable
fun Modifier.flowGlass(
    shape: Shape = CircleShape,
    style: GlassStyle = GlassStyle.LIQUID,
    reduceBlur: Boolean = false,
    containerColor: Color? = null,
): Modifier {
    val dark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    val baseTonal = containerColor ?: MaterialTheme.colorScheme.surfaceContainerHigh
    val primary = MaterialTheme.colorScheme.primary

    if (reduceBlur) {
        // Tier 3: Solid Material 3 Surface
        return this
            .dockShadow(shape)
            .clip(shape)
            .background(baseTonal, shape)
            .dockHighlight(shape)
    }

    // Glass substrates: calibrated translucent optical densities with specular bevel
    val filmColor =
        when (style) {
            GlassStyle.LIQUID -> if (dark) Color(0xB81A1A26) else Color(0xD8F4F4F8)
            GlassStyle.FROSTED -> if (dark) Color(0xD012121A) else Color(0xEBEDEDF2)
            GlassStyle.CLEAR -> if (dark) Color(0x751A1A24) else Color(0x99F4F4F8)
            GlassStyle.HEAVY -> if (dark) Color(0xF0101014) else Color(0xF8E8E8EE)
            GlassStyle.TINTED -> if (dark) primary.copy(alpha = 0.40f) else primary.copy(alpha = 0.28f)
            GlassStyle.SMOKE -> if (dark) Color(0xD50A0A0E) else Color(0xCC202026)
        }

    return this
        .dockShadow(shape)
        .clip(shape)
        .background(filmColor, shape)
        .dockHighlight(shape)
}
