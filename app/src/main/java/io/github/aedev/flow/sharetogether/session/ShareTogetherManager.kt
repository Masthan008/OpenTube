package io.github.aedev.flow.sharetogether.session

import android.content.Context
import androidx.annotation.MainThread
import io.github.aedev.flow.sharetogether.crypto.ShareTogetherCrypto
import io.github.aedev.flow.sharetogether.model.RoomChatMessage
import io.github.aedev.flow.sharetogether.model.RoomControlMode
import io.github.aedev.flow.sharetogether.model.RoomEmojiReaction
import io.github.aedev.flow.sharetogether.model.RoomMember
import io.github.aedev.flow.sharetogether.model.RoomRole
import io.github.aedev.flow.sharetogether.model.ShareTogetherFrame
import io.github.aedev.flow.sharetogether.model.SyncMediaState
import io.github.aedev.flow.sharetogether.net.NostrRelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

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
 * Singleton managing Share Together room lifecycle over decentralized Nostr relays.
 * Enables zero-cost, end-to-end encrypted room connections across any network (Chennai ↔ Hyderabad).
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

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _connectedRelaysCount = MutableStateFlow(0)
    val connectedRelaysCount: StateFlow<Int> = _connectedRelaysCount.asStateFlow()

    private val _remoteMediaState = MutableStateFlow<SyncMediaState?>(null)
    val remoteMediaState: StateFlow<SyncMediaState?> = _remoteMediaState.asStateFlow()

    private var relayPool: NostrRelayPool? = null
    private var activeSecretKey: SecretKeySpec? = null
    private var activeTopicTag: String? = null
    private var eventConsumerJob: Job? = null
    private var hostBroadcastJob: Job? = null
    private var joinKnockJob: Job? = null

    val isRoomActive: Boolean
        get() = _session.value != null

    val isHost: Boolean
        get() = _session.value?.role == RoomRole.HOST

    /**
     * Create and host a new Share Together room.
     */
    fun createRoom(
        context: Context? = null,
        hostName: String,
        avatarId: Int,
        controlMode: RoomControlMode = RoomControlMode.PARTY,
    ): String {
        leaveRoom()

        val roomCode = ShareTogetherCrypto.generateRoomCode()
        val topicTag = ShareTogetherCrypto.computeTopicTag(roomCode)
        val secretKey = ShareTogetherCrypto.deriveSecretKey(roomCode)

        activeTopicTag = topicTag
        activeSecretKey = secretKey

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
                hostAddress = "Global Nostr Relays",
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
                    text = "Room $roomCode created! Share the code with friends anywhere in the world to join.",
                    isSystem = true,
                ),
            )

        // Start Nostr Relay Pool
        val pool = NostrRelayPool()
        relayPool = pool
        pool.start(topicTag)

        // Observe connected relays count
        scope.launch {
            pool.connectedCount.collect { count ->
                _connectedRelaysCount.value = count
            }
        }

        // Listen for incoming frames from participants
        eventConsumerJob =
            scope.launch(Dispatchers.IO) {
                pool.events.collect { event ->
                    val decryptedJson = ShareTogetherCrypto.open(secretKey, topicTag, event.content) ?: return@collect
                    try {
                        val frame = json.decodeFromString(ShareTogetherFrame.serializer(), decryptedJson)
                        handleIncomingHostFrame(frame)
                    } catch (_: Exception) {
                    }
                }
            }

        // Periodic host sync heartbeat (every 1.5s) to keep all participants aligned
        hostBroadcastJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(1500)
                    val curSession = _session.value ?: break
                    if (curSession.role == RoomRole.HOST) {
                        val stateFrame =
                            ShareTogetherFrame.RoomState(
                                roomCode = curSession.roomCode,
                                hostId = curSession.myMemberId,
                                members = _members.value,
                                controlMode = curSession.controlMode,
                                mediaState = _remoteMediaState.value,
                            )
                        publishFrame(stateFrame)
                    }
                }
            }

        return roomCode
    }

    /**
     * Join an existing Share Together room using room code.
     */
    fun joinRoom(
        context: Context? = null,
        roomCodeOrAddress: String,
        memberName: String,
        avatarId: Int,
        onResult: (Boolean) -> Unit = {},
    ) {
        leaveRoom()
        _isConnecting.value = true

        val cleanCode = roomCodeOrAddress.trim().uppercase()
        val topicTag = ShareTogetherCrypto.computeTopicTag(cleanCode)
        val secretKey = ShareTogetherCrypto.deriveSecretKey(cleanCode)

        activeTopicTag = topicTag
        activeSecretKey = secretKey

        val myMemberId = UUID.randomUUID().toString()
        val preliminarySession =
            ShareTogetherSession(
                roomCode = cleanCode,
                role = RoomRole.PARTICIPANT,
                myMemberId = myMemberId,
                myName = memberName,
                myAvatarId = avatarId,
                hostId = "",
                hostAddress = "Global Nostr Relays",
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
                    text = "Connecting to room $cleanCode over global relays...",
                    isSystem = true,
                ),
            )

        val pool = NostrRelayPool()
        relayPool = pool
        pool.start(topicTag)

        scope.launch {
            pool.connectedCount.collect { count ->
                _connectedRelaysCount.value = count
            }
        }

        var isRoomAcknowledged = false

        // Listen for incoming frames from host
        eventConsumerJob =
            scope.launch(Dispatchers.IO) {
                pool.events.collect { event ->
                    val decryptedJson = ShareTogetherCrypto.open(secretKey, topicTag, event.content) ?: return@collect
                    try {
                        val frame = json.decodeFromString(ShareTogetherFrame.serializer(), decryptedJson)
                        handleIncomingParticipantFrame(frame)
                        if (!isRoomAcknowledged && (frame is ShareTogetherFrame.RoomState || frame is ShareTogetherFrame.MemberJoined)) {
                            isRoomAcknowledged = true
                            withContext(Dispatchers.Main) {
                                _isConnecting.value = false
                                onResult(true)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }

        // Send knock / join frame repeatedly until acknowledged or timeout (12s)
        joinKnockJob =
            scope.launch(Dispatchers.IO) {
                val joinFrame = ShareTogetherFrame.Join(member = myMember, roomCode = cleanCode)
                var attempts = 0
                while (isActive && !isRoomAcknowledged && attempts < 6) {
                    publishFrame(joinFrame)
                    delay(2000)
                    attempts++
                }
                if (!isRoomAcknowledged) {
                    withContext(Dispatchers.Main) {
                        _isConnecting.value = false
                        _chatMessages.value +=
                            RoomChatMessage(
                                senderId = "system",
                                senderName = "OpenTube",
                                text = "Could not find host for room $cleanCode. Make sure the code is correct and the host is active.",
                                isSystem = true,
                            )
                        onResult(false)
                    }
                }
            }
    }

    /**
     * Handle incoming frames on Host.
     */
    private fun handleIncomingHostFrame(frame: ShareTogetherFrame) {
        scope.launch(Dispatchers.Main) {
            val currentSession = _session.value ?: return@launch
            when (frame) {
                is ShareTogetherFrame.Join -> {
                    val newMember = frame.member
                    if (_members.value.none { it.id == newMember.id }) {
                        val updated = _members.value + newMember
                        _members.value = updated

                        // Send complete room state back
                        val roomState =
                            ShareTogetherFrame.RoomState(
                                roomCode = currentSession.roomCode,
                                hostId = currentSession.myMemberId,
                                members = updated,
                                controlMode = currentSession.controlMode,
                                mediaState = _remoteMediaState.value,
                            )
                        publishFrame(roomState)

                        // Announce member joined
                        val sysMsg =
                            RoomChatMessage(
                                senderId = "system",
                                senderName = "OpenTube",
                                text = "${newMember.name} joined the room",
                                isSystem = true,
                            )
                        _chatMessages.value += sysMsg
                        publishFrame(ShareTogetherFrame.Chat(sysMsg))
                    }
                }

                is ShareTogetherFrame.Chat -> {
                    if (_chatMessages.value.none { it.id == frame.message.id }) {
                        _chatMessages.value += frame.message
                    }
                }

                is ShareTogetherFrame.Reaction -> {
                    if (_recentReactions.value.none { it.id == frame.reaction.id }) {
                        _recentReactions.value += frame.reaction
                        scope.launch {
                            delay(3500)
                            _recentReactions.value = _recentReactions.value.filter { it.id != frame.reaction.id }
                        }
                    }
                }

                is ShareTogetherFrame.VoiceStatus -> {
                    _members.value =
                        _members.value.map { m ->
                            if (m.id == frame.memberId) m.copy(isMuted = frame.isMuted, isSpeaking = frame.isSpeaking) else m
                        }
                }

                is ShareTogetherFrame.SyncMedia -> {
                    if (currentSession.controlMode == RoomControlMode.PARTY) {
                        _remoteMediaState.value = frame.mediaState
                        publishFrame(frame)
                    }
                }

                is ShareTogetherFrame.Leave -> {
                    val leavingMember = _members.value.find { it.id == frame.memberId }
                    if (leavingMember != null) {
                        _members.value = _members.value.filter { it.id != frame.memberId }
                        val sysMsg =
                            RoomChatMessage(
                                senderId = "system",
                                senderName = "OpenTube",
                                text = "${leavingMember.name} left the room",
                                isSystem = true,
                            )
                        _chatMessages.value += sysMsg
                    }
                }

                else -> {}
            }
        }
    }

    /**
     * Handle incoming frames on Participant.
     */
    private fun handleIncomingParticipantFrame(frame: ShareTogetherFrame) {
        scope.launch(Dispatchers.Main) {
            when (frame) {
                is ShareTogetherFrame.RoomState -> {
                    _session.value =
                        _session.value?.copy(
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
                    if (_chatMessages.value.none { it.id == frame.message.id }) {
                        _chatMessages.value += frame.message
                    }
                }

                is ShareTogetherFrame.Reaction -> {
                    if (_recentReactions.value.none { it.id == frame.reaction.id }) {
                        _recentReactions.value += frame.reaction
                        scope.launch {
                            delay(3500)
                            _recentReactions.value = _recentReactions.value.filter { it.id != frame.reaction.id }
                        }
                    }
                }

                is ShareTogetherFrame.VoiceStatus -> {
                    _members.value =
                        _members.value.map { m ->
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
     * Encrypt and publish a frame to the relay pool.
     */
    private fun publishFrame(frame: ShareTogetherFrame) {
        val pool = relayPool ?: return
        val key = activeSecretKey ?: return
        val tag = activeTopicTag ?: return

        try {
            val plainJson = json.encodeToString(ShareTogetherFrame.serializer(), frame)
            val sealedCiphertext = ShareTogetherCrypto.seal(key, tag, plainJson)
            pool.publish(sealedCiphertext, tag)
        } catch (_: Exception) {
        }
    }

    /**
     * Leave the room and clean up state.
     */
    fun leaveRoom() {
        val cur = _session.value
        if (cur != null) {
            publishFrame(ShareTogetherFrame.Leave(cur.myMemberId))
        }

        eventConsumerJob?.cancel()
        eventConsumerJob = null
        hostBroadcastJob?.cancel()
        hostBroadcastJob = null
        joinKnockJob?.cancel()
        joinKnockJob = null

        relayPool?.close()
        relayPool = null
        activeSecretKey = null
        activeTopicTag = null

        _session.value = null
        _members.value = emptyList()
        _chatMessages.value = emptyList()
        _recentReactions.value = emptyList()
        _remoteMediaState.value = null
        _isConnecting.value = false
        _connectedRelaysCount.value = 0
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
        publishFrame(ShareTogetherFrame.Chat(msg))
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
        publishFrame(ShareTogetherFrame.Reaction(reaction))

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

        val voiceFrame =
            ShareTogetherFrame.VoiceStatus(
                memberId = currentSession.myMemberId,
                isMuted = newMute,
                isSpeaking = false,
            )
        publishFrame(voiceFrame)
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
        val sysMsg =
            RoomChatMessage(
                senderId = "system",
                senderName = "OpenTube",
                senderAvatarId = 0,
                text = "Room mode changed to $modeText",
                isSystem = true,
            )
        _chatMessages.value += sysMsg

        publishFrame(ShareTogetherFrame.UpdateControlMode(mode))
        publishFrame(ShareTogetherFrame.Chat(sysMsg))
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
        publishFrame(ShareTogetherFrame.SyncMedia(state))
    }

    /**
     * Apply remote media state received from host or another member.
     */
    fun applyRemoteMediaState(state: SyncMediaState) {
        _remoteMediaState.value = state
    }
}
