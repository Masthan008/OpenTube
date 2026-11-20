package io.github.aedev.flow.ui.components.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Common data model representing active media for the Island Capsule
 */
data class IslandMedia(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String,
    val isPlaying: Boolean,
    val isMusic: Boolean,
)

/**
 * Origin Island / Dynamic Capsule player for OpenTube.
 *
 * Floats elegantly below the camera punch-hole / status bar when audio or video is playing
 * in the background or while navigating feeds and settings.
 *
 * Supports BOTH:
 * 1. Native Music Player tracks (EnhancedMusicPlayerManager)
 * 2. YouTube Videos / Background Video Audio (GlobalPlayerState / EnhancedPlayerManager)
 *
 * Features:
 * - Micro rotating vinyl disc of media thumbnail
 * - Media title with smooth marquee and artist/channel name
 * - Live animated 3-bar audio wave visualizer
 * - Quick play/pause control
 * - Tap to expand full player sheet (music or video)
 * - Swipe up to dismiss
 */
@Composable
fun OpenTubeIslandHost(
    isMusicPlayerExpanded: Boolean = false,
    isVideoPlayerExpanded: Boolean = false,
    onExpandMusicPlayer: () -> Unit = {},
    onExpandVideoPlayer: () -> Unit = {},
    isFullPlayerExpanded: Boolean = false,
    onExpandPlayer: () -> Unit = {},
    hasActiveRoom: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val preferences = remember(context) { PlayerPreferences(context) }
    val islandEnabled by preferences.originIslandEnabled.collectAsStateWithLifecycle(initialValue = true)

    if (!islandEnabled || hasActiveRoom) return

    // 1. Music track & state
    val currentTrack by EnhancedMusicPlayerManager.currentTrack.collectAsStateWithLifecycle(initialValue = null)
    val isMusicPlaying by remember {
        EnhancedMusicPlayerManager.playerState
            .map { it.isPlaying }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    // 2. Video & state
    val currentVideo by GlobalPlayerState.currentVideo.collectAsStateWithLifecycle(initialValue = null)
    val isVideoPlaying by remember {
        GlobalPlayerState.playerState
            .map { it.isPlaying }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    // Active media resolution
    val media by remember(currentTrack, isMusicPlaying, currentVideo, isVideoPlaying) {
        derivedStateOf {
            val track = currentTrack
            val video = currentVideo
            when {
                track != null -> {
                    IslandMedia(
                        id = track.videoId,
                        title = track.title,
                        subtitle = track.artist,
                        thumbnailUrl = track.listThumbnailUrl,
                        isPlaying = isMusicPlaying,
                        isMusic = true,
                    )
                }
                video != null -> {
                    IslandMedia(
                        id = video.id,
                        title = video.title,
                        subtitle = video.channelName,
                        thumbnailUrl = video.thumbnailUrl,
                        isPlaying = isVideoPlaying,
                        isMusic = false,
                    )
                }
                else -> null
            }
        }
    }

    var userDismissedMediaId by remember { mutableStateOf<String?>(null) }
    var pauseGracePeriod by remember { mutableStateOf(false) }

    val activeMedia = media
    LaunchedEffect(activeMedia?.id) {
        if (activeMedia != null && activeMedia.id != userDismissedMediaId) {
            userDismissedMediaId = null
        }
    }

    LaunchedEffect(activeMedia?.isPlaying) {
        if (activeMedia?.isPlaying == true) {
            pauseGracePeriod = true
        } else if (activeMedia != null) {
            delay(3500)
            pauseGracePeriod = false
        } else {
            pauseGracePeriod = false
        }
    }

    val isExpanded =
        when {
            activeMedia?.isMusic == true -> isMusicPlayerExpanded || isFullPlayerExpanded
            activeMedia != null -> isVideoPlayerExpanded || isFullPlayerExpanded
            else -> false
        }

    val visible by remember(activeMedia, isExpanded, userDismissedMediaId, pauseGracePeriod) {
        derivedStateOf {
            activeMedia != null &&
                (activeMedia.isPlaying || pauseGracePeriod) &&
                !isExpanded &&
                userDismissedMediaId != activeMedia.id
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter =
            slideInVertically(
                initialOffsetY = { -it },
                animationSpec = spring(dampingRatio = 0.74f, stiffness = 420f),
            ) + fadeIn(animationSpec = tween(180)),
        exit =
            slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = tween(180),
            ) + fadeOut(animationSpec = tween(140)),
        modifier =
            modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 4.dp, start = 16.dp, end = 16.dp),
    ) {
        if (activeMedia != null) {
            IslandCapsule(
                media = activeMedia,
                isPlaying = activeMedia.isPlaying,
                onTap = {
                    if (activeMedia.isMusic) {
                        onExpandMusicPlayer()
                        onExpandPlayer()
                    } else {
                        onExpandVideoPlayer()
                        onExpandPlayer()
                    }
                },
                onDismiss = { userDismissedMediaId = activeMedia.id },
                onPlayPause = {
                    if (activeMedia.isMusic) {
                        EnhancedMusicPlayerManager.togglePlayPause()
                    } else {
                        GlobalPlayerState.togglePlayPause()
                    }
                },
            )
        }
    }
}

/**
 * Visual pill capsule for Origin Island
 */
@Composable
fun IslandCapsule(
    media: IslandMedia,
    isPlaying: Boolean,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val containerShape = RoundedCornerShape(20.dp)

    // Subtle stretch animation when mounted
    val stretch = remember { Animatable(0.85f) }
    LaunchedEffect(media.id) {
        stretch.snapTo(0.85f)
        stretch.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 380f))
    }

    Surface(
        shape = containerShape,
        color = colorScheme.surfaceContainerHighest,
        shadowElevation = 8.dp,
        modifier =
            modifier
                .fillMaxWidth()
                .wrapIslandWidth()
                .height(42.dp)
                .graphicsLayer {
                    val scale = stretch.value
                    scaleX = scale
                    scaleY = scale
                }
                .border(
                    width = 1.dp,
                    color = colorScheme.outlineVariant.copy(alpha = 0.35f),
                    shape = containerShape,
                )
                .pointerInput(media.id) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount < -12f) {
                            onDismiss()
                        }
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTap,
                ),
    ) {
        Row(
            modifier =
                Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Media thumbnail with spinning vinyl record effect
            IslandCoverDisc(
                thumbnailUrl = media.thumbnailUrl,
                isPlaying = isPlaying,
                modifier = Modifier.size(32.dp),
            )

            Spacer(Modifier.width(8.dp))

            // Track info with marquee
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(vertical = 1.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = media.title,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(),
                )
                Text(
                    text = media.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))

            // Mini visualizer audio bars
            IslandEqualizerBars(
                isPlaying = isPlaying,
                modifier = Modifier.size(width = 18.dp, height = 16.dp),
            )

            Spacer(Modifier.width(4.dp))

            // Play / Pause quick button
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                    tint = colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Wraps and caps the island width responsibly for compact and wide screens */
private fun Modifier.wrapIslandWidth(): Modifier = this.widthIn(min = 260.dp, max = 380.dp)

@Composable
private fun IslandCoverDisc(
    thumbnailUrl: String,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    // Only spin when playing to conserve battery & adhere to invisible animation rule
    val infiniteTransition = rememberInfiniteTransition(label = "island_disc")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(12000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "disc_rotation",
    )

    val currentRotation = if (isPlaying) rotation else 0f
    val context = LocalContext.current

    Box(
        modifier =
            modifier
                .clip(CircleShape)
                .graphicsLayer { rotationZ = currentRotation },
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnailUrl.isNotBlank()) {
            AsyncImage(
                model =
                    ImageRequest.Builder(context)
                        .data(thumbnailUrl)
                        .crossfade(true)
                        .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(32.dp),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_notification_logo),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Center vinyl spindle hole
        Box(
            modifier =
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color.Black),
        )
    }
}

@Composable
private fun IslandEqualizerBars(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary

    // Animated bar heights that oscillate only when playing
    val infiniteTransition = rememberInfiniteTransition(label = "island_bars")
    val bar1 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.95f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(450, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "bar1",
    )
    val bar2 by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.25f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(380, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "bar2",
    )
    val bar3 by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(520, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "bar3",
    )

    Canvas(modifier = modifier) {
        val barWidth = 3.dp.toPx()
        val spacing = 3.dp.toPx()
        val maxHeight = size.height

        val h1 = if (isPlaying) bar1 * maxHeight else 0.35f * maxHeight
        val h2 = if (isPlaying) bar2 * maxHeight else 0.2f * maxHeight
        val h3 = if (isPlaying) bar3 * maxHeight else 0.45f * maxHeight

        val corner = CornerRadius(2.dp.toPx())

        // Bar 1
        drawRoundRect(
            color = primary,
            topLeft = Offset(0f, maxHeight - h1),
            size = Size(barWidth, h1),
            cornerRadius = corner,
        )
        // Bar 2
        drawRoundRect(
            color = primary,
            topLeft = Offset(barWidth + spacing, maxHeight - h2),
            size = Size(barWidth, h2),
            cornerRadius = corner,
        )
        // Bar 3
        drawRoundRect(
            color = primary,
            topLeft = Offset((barWidth + spacing) * 2f, maxHeight - h3),
            size = Size(barWidth, h3),
            cornerRadius = corner,
        )
    }
}

/**
 * Interactive preview of the Origin Island Capsule for settings pages.
 */
@Composable
fun OpenTubeIslandPreview(
    modifier: Modifier = Modifier,
) {
    var isPlaying by remember { mutableStateOf(true) }
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        IslandCapsule(
            media =
                IslandMedia(
                    id = "preview",
                    title = "OpenTube Island Capsule",
                    subtitle = "Now Playing • Dynamic Status Pill",
                    thumbnailUrl = "",
                    isPlaying = isPlaying,
                    isMusic = true,
                ),
            isPlaying = isPlaying,
            onTap = { isPlaying = !isPlaying },
            onDismiss = {},
            onPlayPause = { isPlaying = !isPlaying },
        )
    }
}
