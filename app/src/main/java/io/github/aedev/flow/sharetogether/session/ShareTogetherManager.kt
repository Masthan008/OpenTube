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
import io.github.aedev.flow.sharetogether.net.ShareTogetherNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val hostAddress: String = "",
    val controlMode: RoomControlMode = RoomControlMode.PARTY,
)

/**
 * Singleton managing Share Together room lifecycle, network frames, participant state,
 * real-time chat, floating reactions, and voice toggles.
 */
object ShareTogetherManager {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

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
        context: Context,
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
                    text = "Room $roomCode created! Share code $roomCode with friends on your Wi-Fi or Hotspot to join.",
                    isSystem = true,
                ),
            )

        // Launch embedded server & UDP beacon
        scope.launch(Dispatchers.IO) {
            val (ip, port) = ShareTogetherNetwork.startHosting(context, roomCode) { frame, connectionId ->
                handleIncomingHostFrame(frame, connectionId)
            }
            withContext(Dispatchers.Main) {
                _session.value = _session.value?.copy(hostAddress = "$ip:$port")
            }
        }

        return roomCode
    }

    /**
     * Join an existing Share Together room with room code or IP address.
     */
    fun joinRoom(
        context: Context,
        roomCodeOrAddress: String,
        memberName: String,
        avatarId: Int,
        onResult: (Boolean) -> Unit = {},
    ) {
        leaveRoom()
        _isConnecting.value = true

        val myMemberId = UUID.randomUUID().toString()
        val formattedCode = roomCodeOrAddress.trim().uppercase()

        val preliminarySession =
            ShareTogetherSession(
                roomCode = formattedCode,
                role = RoomRole.PARTICIPANT,
                myMemberId = myMemberId,
                myName = memberName,
                myAvatarId = avatarId,
                hostId = "",
            )
        _session.value = preliminarySession

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
                    text = "Searching for room $formattedCode on local network...",
                    isSystem = true,
                ),
            )

        scope.launch(Dispatchers.IO) {
            val connected = ShareTogetherNetwork.joinHost(
                context = context,
                inputCodeOrAddress = formattedCode,
                onFrameReceived = { frame -> handleIncomingClientFrame(frame) },
                onDisconnected = {
                    scope.launch(Dispatchers.Main) {
                        _chatMessages.value += RoomChatMessage(
                            senderId = "system",
                            senderName = "OpenTube",
                            text = "Disconnected from host.",
                            isSystem = true,
                        )
                    }
                },
            )

            withContext(Dispatchers.Main) {
                _isConnecting.value = false
                if (connected) {
                    val joinFrame = ShareTogetherFrame.Join(member = myMember, roomCode = formattedCode)
                    ShareTogetherNetwork.sendToHost(joinFrame)
                    _chatMessages.value += RoomChatMessage(
                        senderId = "system",
                        senderName = "OpenTube",
                        text = "Connected to room $formattedCode!",
                        isSystem = true,
                    )
                    onResult(true)
                } else {
                    _chatMessages.value += RoomChatMessage(
                        senderId = "system",
                        senderName = "OpenTube",
                        text = "Could not find room $formattedCode. Ensure both devices are on the same Wi-Fi / Hotspot or enter host IP.",
                        isSystem = true,
                    )
                    onResult(false)
                }
            }
        }
    }

    /**
     * Handle incoming wire frames on the Host.
     */
    private fun handleIncomingHostFrame(frame: ShareTogetherFrame, connectionId: String) {
        scope.launch(Dispatchers.Main) {
            val currentSession = _session.value ?: return@launch
            when (frame) {
                is ShareTogetherFrame.Join -> {
                    val newMember = frame.member
                    val existing = _members.value.filter { it.id != newMember.id }
                    val updated = existing + newMember
                    _members.value = updated

                    // Send complete room state back to the newly joined participant
                    val roomState = ShareTogetherFrame.RoomState(
                        roomCode = currentSession.roomCode,
                        hostId = currentSession.myMemberId,
                        members = updated,
                        controlMode = currentSession.controlMode,
                        mediaState = _remoteMediaState.value,
                    )
                    ShareTogetherNetwork.sendToParticipant(connectionId, roomState)

                    // Announce new member to everyone else
                    ShareTogetherNetwork.broadcastToParticipants(ShareTogetherFrame.MemberJoined(newMember))

                    // System chat message
                    val sysMsg = RoomChatMessage(
                        senderId = "system",
                        senderName = "OpenTube",
                        text = "${newMember.name} joined the room",
                        isSystem = true,
                    )
                    _chatMessages.value += sysMsg
                    ShareTogetherNetwork.broadcastToParticipants(ShareTogetherFrame.Chat(sysMsg))
                }

                is ShareTogetherFrame.Chat -> {
                    _chatMessages.value += frame.message
                    ShareTogetherNetwork.broadcastToParticipants(frame)
                }

                is ShareTogetherFrame.Reaction -> {
                    _recentReactions.value += frame.reaction
                    ShareTogetherNetwork.broadcastToParticipants(frame)
                    scope.launch {
                        delay(3500)
                        _recentReactions.value = _recentReactions.value.filter { it.id != frame.reaction.id }
                    }
                }

                is ShareTogetherFrame.VoiceStatus -> {
                    _members.value = _members.value.map { m ->
                        if (m.id == frame.memberId) m.copy(isMuted = frame.isMuted, isSpeaking = frame.isSpeaking) else m
                    }
                    ShareTogetherNetwork.broadcastToParticipants(frame)
                }

                is ShareTogetherFrame.SyncMedia -> {
                    if (currentSession.controlMode == RoomControlMode.PARTY) {
                        _remoteMediaState.value = frame.mediaState
                        ShareTogetherNetwork.broadcastToParticipants(frame)
                    }
                }

                is ShareTogetherFrame.Leave -> {
                    val leavingMember = _members.value.find { it.id == frame.memberId }
                    _members.value = _members.value.filter { it.id != frame.memberId }
                    if (leavingMember != null) {
                        val leftFrame = ShareTogetherFrame.MemberLeft(leavingMember.id, leavingMember.name)
                        ShareTogetherNetwork.broadcastToParticipants(leftFrame)
                        val sysMsg = RoomChatMessage(
                            senderId = "system",
                            senderName = "OpenTube",
                            text = "${leavingMember.name} left the room",
                            isSystem = true,
                        )
                        _chatMessages.value += sysMsg
                        ShareTogetherNetwork.broadcastToParticipants(ShareTogetherFrame.Chat(sysMsg))
                    }
                }

                else -> {}
            }
        }
    }

    /**
     * Handle incoming wire frames on Participants.
     */
    private fun handleIncomingClientFrame(frame: ShareTogetherFrame) {
        scope.launch(Dispatchers.Main) {
            when (frame) {
                is ShareTogetherFrame.RoomState -> {
                    _session.value = _session.value?.copy(
                        hostId = frame.hostId,
                        controlMode = frame.controlMode,
                    )
                    _members.value = frame.members
                    if (frame.mediaState != null) {
                        _remoteMediaState.value = frame.mediaState
                    }
                }

                is ShareTogetherFrame.MemberJoined -> {
                    val newMember = frame.member
                    if (_members.value.none { it.id == newMember.id }) {
                        _members.value += newMember
                    }
                }

                is ShareTogetherFrame.MemberLeft -> {
                    _members.value = _members.value.filter { it.id != frame.memberId }
                }

                is ShareTogetherFrame.Chat -> {
                    _chatMessages.value += frame.message
                }

                is ShareTogetherFrame.Reaction -> {
                    _recentReactions.value += frame.reaction
                    scope.launch {
                        delay(3500)
                        _recentReactions.value = _recentReactions.value.filter { it.id != frame.reaction.id }
                    }
                }

                is ShareTogetherFrame.VoiceStatus -> {
                    _members.value = _members.value.map { m ->
                        if (m.id == frame.memberId) m.copy(isMuted = frame.isMuted, isSpeaking = frame.isSpeaking) else m
                    }
                }

                is ShareTogetherFrame.SyncMedia -> {
                    _remoteMediaState.value = frame.mediaState
                }

                is ShareTogetherFrame.UpdateControlMode -> {
                    _session.value = _session.value?.copy(controlMode = frame.mode)
                }

                else -> {}
            }
        }
    }

    /**
     * Leave the room and clean up state.
     */
    fun leaveRoom() {
        val cur = _session.value
        if (cur != null) {
            if (cur.role == RoomRole.HOST) {
                ShareTogetherNetwork.broadcastToParticipants(ShareTogetherFrame.MemberLeft(cur.myMemberId, cur.myName))
                ShareTogetherNetwork.stopHosting()
            } else {
                ShareTogetherNetwork.sendToHost(ShareTogetherFrame.Leave(cur.myMemberId))
                ShareTogetherNetwork.disconnectClient()
            }
        }

        _session.value = null
        _members.value = emptyList()
        _chatMessages.value = emptyList()
        _recentReactions.value = emptyList()
        _remoteMediaState.value = null
        _isConnecting.value = false
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

        val chatFrame = ShareTogetherFrame.Chat(msg)
        if (currentSession.role == RoomRole.HOST) {
            ShareTogetherNetwork.broadcastToParticipants(chatFrame)
        } else {
            ShareTogetherNetwork.sendToHost(chatFrame)
        }
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

        val reactionFrame = ShareTogetherFrame.Reaction(reaction)
        if (currentSession.role == RoomRole.HOST) {
            ShareTogetherNetwork.broadcastToParticipants(reactionFrame)
        } else {
            ShareTogetherNetwork.sendToHost(reactionFrame)
        }

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

        val voiceFrame = ShareTogetherFrame.VoiceStatus(
            memberId = currentSession.myMemberId,
            isMuted = newMute,
            isSpeaking = false,
        )
        if (currentSession.role == RoomRole.HOST) {
            ShareTogetherNetwork.broadcastToParticipants(voiceFrame)
        } else {
            ShareTogetherNetwork.sendToHost(voiceFrame)
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
        val sysMsg = RoomChatMessage(
            senderId = "system",
            senderName = "OpenTube",
            senderAvatarId = 0,
            text = "Room mode changed to $modeText",
            isSystem = true,
        )
        _chatMessages.value += sysMsg

        val updateFrame = ShareTogetherFrame.UpdateControlMode(mode)
        ShareTogetherNetwork.broadcastToParticipants(updateFrame)
        ShareTogetherNetwork.broadcastToParticipants(ShareTogetherFrame.Chat(sysMsg))
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

        val syncFrame = ShareTogetherFrame.SyncMedia(state)
        if (currentSession.role == RoomRole.HOST) {
            ShareTogetherNetwork.broadcastToParticipants(syncFrame)
        } else {
            ShareTogetherNetwork.sendToHost(syncFrame)
        }
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
