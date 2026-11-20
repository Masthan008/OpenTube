package io.github.aedev.flow.sharetogether.session

import android.content.Context
import androidx.annotation.MainThread
import io.github.aedev.flow.sharetogether.model.RoomChatMessage
import io.github.aedev.flow.sharetogether.model.RoomControlMode
import io.github.aedev.flow.sharetogether.model.RoomEmojiReaction
import io.github.aedev.flow.sharetogether.model.RoomMember
import io.github.aedev.flow.sharetogether.model.RoomRole
import io.github.aedev.flow.sharetogether.model.ShareTogetherFrame
import io.github.aedev.flow.sharetogether.model.SyncMediaState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.random.Random

/**
 * Live active session metadata for Share Together.
 */
data class ShareTogetherSession(
    val roomCode: String,
    val role: RoomRole,
    val myMemberId: String,
    val myName: String,
    val myAvatarId: Int,
    val hostId: String,
    val controlMode: RoomControlMode = RoomControlMode.PARTY,
)

/**
 * Singleton managing Share Together room lifecycle, network frames, participant state,
 * real-time chat, floating reactions, and voice toggles.
 */
object ShareTogetherManager {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    private val _session = MutableStateFlow<ShareTogetherSession?>(null)
    val session: StateFlow<ShareTogetherSession?> = _session.asStateFlow()

    private val _members = MutableStateFlow<List<RoomMember>>(emptyList())
    val members: StateFlow<List<RoomMember>> = _members.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<RoomChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<RoomChatMessage>> = _chatMessages.asStateFlow()

    private val _recentReactions = MutableStateFlow<List<RoomEmojiReaction>>(emptyList())
    val recentReactions: StateFlow<List<RoomEmojiReaction>> = _recentReactions.asStateFlow()

    private val _isMicMuted = MutableStateFlow(true)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _isDeafened = MutableStateFlow(false)
    val isDeafened: StateFlow<Boolean> = _isDeafened.asStateFlow()

    private val _remoteMediaState = MutableStateFlow<SyncMediaState?>(null)
    val remoteMediaState: StateFlow<SyncMediaState?> = _remoteMediaState.asStateFlow()

    val isRoomActive: Boolean
        get() = _session.value != null

    val isHost: Boolean
        get() = _session.value?.role == RoomRole.HOST

    /**
     * Create and host a new Share Together room.
     */
    fun createRoom(
        hostName: String,
        avatarId: Int,
        controlMode: RoomControlMode = RoomControlMode.PARTY,
    ): String {
        leaveRoom()

        val roomCode = generateRoomCode()
        val hostMemberId = UUID.randomUUID().toString()
        val hostMember =
            RoomMember(
                id = hostMemberId,
                name = hostName,
                avatarId = avatarId,
                isHost = true,
                isMuted = _isMicMuted.value,
            )

        val newSession =
            ShareTogetherSession(
                roomCode = roomCode,
                role = RoomRole.HOST,
                myMemberId = hostMemberId,
                myName = hostName,
                myAvatarId = avatarId,
                hostId = hostMemberId,
                controlMode = controlMode,
            )

        _session.value = newSession
        _members.value = listOf(hostMember)
        _chatMessages.value =
            listOf(
                RoomChatMessage(
                    senderId = "system",
                    senderName = "OpenTube",
                    senderAvatarId = 0,
                    text = "Room $roomCode created! Share the code with friends to watch & listen together.",
                    isSystem = true,
                ),
            )

        return roomCode
    }

    /**
     * Join an existing Share Together room with room code.
     */
    fun joinRoom(
        roomCode: String,
        memberName: String,
        avatarId: Int,
    ) {
        leaveRoom()

        val myMemberId = UUID.randomUUID().toString()
        val formattedCode = roomCode.trim().uppercase()

        val newSession =
            ShareTogetherSession(
                roomCode = formattedCode,
                role = RoomRole.PARTICIPANT,
                myMemberId = myMemberId,
                myName = memberName,
                myAvatarId = avatarId,
                hostId = "",
            )

        _session.value = newSession
        val myMember =
            RoomMember(
                id = myMemberId,
                name = memberName,
                avatarId = avatarId,
                isHost = false,
                isMuted = _isMicMuted.value,
            )
        _members.value = listOf(myMember)
        _chatMessages.value =
            listOf(
                RoomChatMessage(
                    senderId = "system",
                    senderName = "OpenTube",
                    senderAvatarId = 0,
                    text = "Joined room $formattedCode! Connecting to host...",
                    isSystem = true,
                ),
            )
    }

    /**
     * Leave the room and clean up state.
     */
    fun leaveRoom() {
        _session.value = null
        _members.value = emptyList()
        _chatMessages.value = emptyList()
        _recentReactions.value = emptyList()
        _remoteMediaState.value = null
    }

    /**
     * Send a text chat message in the room.
     */
    fun sendChatMessage(text: String) {
        val currentSession = _session.value ?: return
        if (text.isBlank()) return

        val msg =
            RoomChatMessage(
                senderId = currentSession.myMemberId,
                senderName = currentSession.myName,
                senderAvatarId = currentSession.myAvatarId,
                text = text.trim(),
            )
        _chatMessages.value += msg
    }

    /**
     * Send a live floating emoji reaction particle burst.
     */
    fun sendEmojiReaction(emoji: String) {
        val currentSession = _session.value ?: return
        val reaction =
            RoomEmojiReaction(
                emoji = emoji,
                senderName = currentSession.myName,
            )
        _recentReactions.value += reaction

        // Auto prune reaction after animation duration
        scope.launch {
            delay(3500)
            _recentReactions.value = _recentReactions.value.filter { it.id != reaction.id }
        }
    }

    /**
     * Toggle microphone mute/unmute.
     */
    fun toggleMicMute() {
        val newMute = !_isMicMuted.value
        _isMicMuted.value = newMute

        val currentSession = _session.value ?: return
        _members.value =
            _members.value.map { member ->
                if (member.id == currentSession.myMemberId) {
                    member.copy(isMuted = newMute, isSpeaking = if (newMute) false else member.isSpeaking)
                } else {
                    member
                }
            }
    }

    /**
     * Toggle deafen (mute incoming voice audio).
     */
    fun toggleDeafen() {
        _isDeafened.value = !_isDeafened.value
    }

    /**
     * Update room control mode (Host DJ vs. Party).
     */
    fun setControlMode(mode: RoomControlMode) {
        val currentSession = _session.value ?: return
        if (currentSession.role != RoomRole.HOST) return

        _session.value = currentSession.copy(controlMode = mode)
        val modeText = if (mode == RoomControlMode.HOST_DJ) "Host DJ Mode" else "Party Mode"
        _chatMessages.value +=
            RoomChatMessage(
                senderId = "system",
                senderName = "OpenTube",
                senderAvatarId = 0,
                text = "Room mode changed to $modeText",
                isSystem = true,
            )
    }

    /**
     * Broadcast media playback state from local player to room members.
     */
    fun broadcastMediaState(state: SyncMediaState) {
        val currentSession = _session.value ?: return
        if (currentSession.role != RoomRole.HOST && currentSession.controlMode == RoomControlMode.HOST_DJ) {
            return
        }
        _remoteMediaState.value = state
    }

    /**
     * Apply remote media state received from host or another member.
     */
    fun applyRemoteMediaState(state: SyncMediaState) {
        _remoteMediaState.value = state
    }

    /**
     * Generate an aesthetic 6-character room code (e.g. "OT-7492").
     */
    private fun generateRoomCode(): String {
        val num = Random.nextInt(1000, 9999)
        return "OT-$num"
    }
}
