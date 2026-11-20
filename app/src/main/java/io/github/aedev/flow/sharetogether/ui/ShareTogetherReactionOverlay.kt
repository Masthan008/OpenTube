package io.github.aedev.flow.sharetogether.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.sharetogether.model.RoomEmojiReaction
import io.github.aedev.flow.sharetogether.session.ShareTogetherManager
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Floating canvas reactions overlay that animates bursts of emojis rising across the player.
 */
@Composable
fun ShareTogetherReactionOverlay(modifier: Modifier = Modifier) {
    val reactions by ShareTogetherManager.recentReactions.collectAsStateWithLifecycle()

    if (reactions.isEmpty()) return

    Box(
        modifier = modifier.fillMaxSize().padding(end = 24.dp, bottom = 96.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        reactions.forEach { reaction ->
            FloatingEmojiParticle(
                reaction = reaction,
                key = reaction.id,
            )
        }
    }
}

@Composable
private fun FloatingEmojiParticle(
    reaction: RoomEmojiReaction,
    key: String,
) {
    val progress = remember(key) { Animatable(0f) }
    val horizontalWobble = remember(key) { Random.nextFloat() * 40f - 20f }

    LaunchedEffect(key) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 3000, easing = LinearEasing),
        )
    }

    val p = progress.value
    if (p >= 1f) return

    val yOffset = (-380.dp.value * p).dp
    val xOffset = (horizontalWobble * sin(p * Math.PI * 3f)).dp
    val alpha = if (p < 0.2f) p / 0.2f else 1f - ((p - 0.2f) / 0.8f)

    Text(
        text = reaction.emoji,
        fontSize = 32.sp,
        modifier =
            Modifier
                .offset { IntOffset(xOffset.roundToPx(), yOffset.roundToPx()) }
                .graphicsLayer {
                    val scale = 0.8f + 0.4f * sin(p * Math.PI.toFloat())
                    scaleX = scale
                    scaleY = scale
                }.alpha(alpha.coerceIn(0f, 1f)),
    )
}
