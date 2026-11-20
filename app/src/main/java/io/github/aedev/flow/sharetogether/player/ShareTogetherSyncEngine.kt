package io.github.aedev.flow.sharetogether.player

import android.content.Context
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.sharetogether.model.RoomControlMode
import io.github.aedev.flow.sharetogether.model.RoomRole
import io.github.aedev.flow.sharetogether.model.SyncMediaState
import io.github.aedev.flow.sharetogether.session.ShareTogetherManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * High-precision playback synchronization and drift correction engine for Share Together.
 *
 * Implements micro-speed interpolation (1.03x / 0.97x) to eliminate seek stutter
 * and auto-ducks media volume during voice chat activity.
 */
object ShareTogetherSyncEngine {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var syncJob: Job? = null
    private var hostBroadcastJob: Job? = null
    private var duckingJob: Job? = null

    private var originalMusicVolume = 1f

    /**
     * Start the sync engine loop.
     */
    fun start() {
        stop()

        // 1. Host broadcasting loop
        hostBroadcastJob =
            scope.launch {
                while (true) {
                    delay(1200)
                    val session = ShareTogetherManager.session.value ?: continue
                    if (session.role != RoomRole.HOST && session.controlMode == RoomControlMode.HOST_DJ) {
                        continue
                    }

                    // Check active player
                    val musicTrack = EnhancedMusicPlayerManager.currentTrack.value
                    val video = GlobalPlayerState.currentVideo.value

                    if (musicTrack != null) {
                        val playerState = EnhancedMusicPlayerManager.playerState.value
                        val mediaState =
                            SyncMediaState(
                                mediaId = musicTrack.videoId,
                                title = musicTrack.title,
                                subtitle = musicTrack.artist,
                                thumbnailUrl = musicTrack.listThumbnailUrl,
                                positionMs = playerState.position,
                                durationMs = playerState.duration,
                                isPlaying = playerState.isPlaying,
                                playbackSpeed = EnhancedMusicPlayerManager.playbackSpeed.value,
                                isMusic = true,
                            )
                        ShareTogetherManager.broadcastMediaState(mediaState)
                    } else if (video != null) {
                        val playerState = GlobalPlayerState.playerState.value
                        val mediaState =
                            SyncMediaState(
                                mediaId = video.id,
                                title = video.title,
                                subtitle = video.channelName,
                                thumbnailUrl = video.thumbnailUrl,
                                positionMs = EnhancedPlayerManager.getInstance().getCurrentPosition(),
                                durationMs = EnhancedPlayerManager.getInstance().getDuration(),
                                isPlaying = playerState.isPlaying,
                                playbackSpeed = playerState.playbackSpeed,
                                isMusic = false,
                            )
                        ShareTogetherManager.broadcastMediaState(mediaState)
                    }
                }
            }

        // 2. Participant synchronization loop
        syncJob =
            scope.launch {
                ShareTogetherManager.remoteMediaState.collectLatest { remote ->
                    if (remote == null) return@collectLatest
                    val session = ShareTogetherManager.session.value ?: return@collectLatest
                    if (session.role == RoomRole.HOST) return@collectLatest

                    applyRemoteMedia(remote)
                }
            }

        // 3. Dynamic voice chat audio ducking
        duckingJob =
            scope.launch {
                combine(
                    ShareTogetherManager.members,
                    ShareTogetherManager.isDeafened,
                ) { members, isDeafened ->
                    !isDeafened && members.any { it.isSpeaking }
                }.collectLatest { someoneSpeaking ->
                    val musicPlayer = EnhancedMusicPlayerManager.playerInstance.value
                    if (someoneSpeaking) {
                        musicPlayer?.volume = 0.70f
                    } else {
                        musicPlayer?.volume = 1.0f
                    }
                }
            }
    }

    /**
     * Stop the sync engine.
     */
    fun stop() {
        hostBroadcastJob?.cancel()
        hostBroadcastJob = null
        syncJob?.cancel()
        syncJob = null
        duckingJob?.cancel()
        duckingJob = null
    }

    private fun applyRemoteMedia(remote: SyncMediaState) {
        if (remote.isMusic) {
            val localTrack = EnhancedMusicPlayerManager.currentTrack.value
            val localState = EnhancedMusicPlayerManager.playerState.value

            // Sync Play/Pause
            if (localState.isPlaying != remote.isPlaying) {
                if (remote.isPlaying) {
                    EnhancedMusicPlayerManager.play()
                } else {
                    EnhancedMusicPlayerManager.pause()
                }
            }

            // Sync Position with micro-speed drift correction
            val elapsedTime = (System.currentTimeMillis() - remote.timestamp).coerceAtLeast(0L)
            val expectedPosition = remote.positionMs + if (remote.isPlaying) elapsedTime else 0L
            val drift = expectedPosition - localState.position

            when {
                // Large drift: Hard seek
                abs(drift) > 1500L -> {
                    EnhancedMusicPlayerManager.seekTo(expectedPosition)
                    EnhancedMusicPlayerManager.setPlaybackSpeed(1.0f)
                }
                // Moderate drift: Smooth speed adjustment
                drift > 150L -> {
                    EnhancedMusicPlayerManager.setPlaybackSpeed(1.03f)
                }
                drift < -150L -> {
                    EnhancedMusicPlayerManager.setPlaybackSpeed(0.97f)
                }
                // In sync: Standard speed
                else -> {
                    if (EnhancedMusicPlayerManager.playbackSpeed.value != 1.0f) {
                        EnhancedMusicPlayerManager.setPlaybackSpeed(1.0f)
                    }
                }
            }
        } else {
            // Video sync
            val isVideoPlaying = GlobalPlayerState.playerState.value.isPlaying
            val videoPos = EnhancedPlayerManager.getInstance().getCurrentPosition()

            if (isVideoPlaying != remote.isPlaying) {
                if (remote.isPlaying) {
                    EnhancedPlayerManager.getInstance().play()
                } else {
                    EnhancedPlayerManager.getInstance().pause()
                }
            }

            val elapsedTime = (System.currentTimeMillis() - remote.timestamp).coerceAtLeast(0L)
            val expectedPosition = remote.positionMs + if (remote.isPlaying) elapsedTime else 0L
            val drift = expectedPosition - videoPos
            if (abs(drift) > 1500L) {
                EnhancedPlayerManager.getInstance().seekTo(expectedPosition)
            }
        }
    }
}
