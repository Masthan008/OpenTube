package io.github.aedev.flow.sharetogether.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.sharetogether.session.ShareTogetherManager

/**
 * Floating status bar pill indicating active Share Together room session.
 */
@Composable
fun ShareTogetherPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session by ShareTogetherManager.session.collectAsStateWithLifecycle()
    val members by ShareTogetherManager.members.collectAsStateWithLifecycle()
    val isMuted by ShareTogetherManager.isMicMuted.collectAsStateWithLifecycle()

    val currentSession = session
    val isVisible = currentSession != null

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier.statusBarsPadding().padding(top = 4.dp),
    ) {
        if (currentSession != null) {
            val colorScheme = MaterialTheme.colorScheme
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = colorScheme.surfaceContainerHighest,
                shadowElevation = 6.dp,
                modifier =
                    Modifier
                        .border(
                            width = 1.dp,
                            color = colorScheme.primary.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(20.dp),
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    // Live green pulse dot
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10B981)),
                    )

                    Spacer(Modifier.width(8.dp))

                    Text(
                        text = "Share Together • ${currentSession.roomCode} (${members.size})",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colorScheme.onSurface,
                    )

                    Spacer(Modifier.width(8.dp))

                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = null,
                        tint = if (isMuted) colorScheme.outline else colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
