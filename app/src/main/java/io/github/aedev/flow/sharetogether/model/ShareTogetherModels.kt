package io.github.aedev.flow.sharetogether.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/** Role of the user in the Share Together room. */
enum class RoomRole {
    HOST,
    PARTICIPANT,
}

/** Operating control permission mode of the room. */
enum class RoomControlMode {
    /** Only the host can pause, seek, and skip tracks. */
    HOST_DJ,

    /** Any participant can pause, play, seek, and add/reorder songs. */
    PARTY,
}

/** Information about a member inside a Share Together room. */
@Serializable
data class RoomMember(
    val id: String,
    val name: String,
    val avatarId: Int = 0,
    val isHost: Boolean = false,
    val isMuted: Boolean = true,
    val isSpeaking: Boolean = false,
    val joinedAt: Long = System.currentTimeMillis(),
)

/** Synchronized media state broadcast between host and participants. */
@Serializable
data class SyncMediaState(
    val mediaId: String = "",
    val title: String = "",
    val subtitle: String = "",
    val thumbnailUrl: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val isMusic: Boolean = true,
    val isLocal: Boolean = false,
    val streamUrl: String = "",
    val timestamp: Long = System.currentTimeMillis(),
)

/** A real-time chat message sent within the room. */
@Serializable
data class RoomChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val senderName: String,
    val senderAvatarId: Int = 0,
    val text: String,
    val isSystem: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)

/** A live floating emoji reaction particle burst. */
@Serializable
data class RoomEmojiReaction(
    val id: String = UUID.randomUUID().toString(),
    val emoji: String,
    val senderName: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/** Wire frames sent across the WebSocket communication channel. */
@Serializable
sealed class ShareTogetherFrame {
    @Serializable
    @SerialName("join")
    data class Join(
        val member: RoomMember,
        val roomCode: String,
    ) : ShareTogetherFrame()

    @Serializable
    @SerialName("room_state")
    data class RoomState(
        val roomCode: String,
        val hostId: String,
        val members: List<RoomMember>,
        val controlMode: RoomControlMode,
        val mediaState: SyncMediaState?,
    ) : ShareTogetherFrame()

    @Serializable
    @SerialName("member_joined")
    data class MemberJoined(
        val member: RoomMember,
    ) : ShareTogetherFrame()

    @Serializable
    @SerialName("member_left")
    data class MemberLeft(
        val memberId: String,
        val memberName: String,
    ) : ShareTogetherFrame()

    @Serializable
    @SerialName("sync_media")
    data class SyncMedia(
        val mediaState: SyncMediaState,
    ) : ShareTogetherFrame()

    @Serializable
    @SerialName("chat")
    data class Chat(
        val message: RoomChatMessage,
    ) : ShareTogetherFrame()

    @Serializable
    @SerialName("reaction")
    data class Reaction(
        val reaction: RoomEmojiReaction,
    ) : ShareTogetherFrame()

    @Serializable
    @SerialName("voice_status")
    data class VoiceStatus(
        val memberId: String,
        val isMuted: Boolean,
        val isSpeaking: Boolean,
    ) : ShareTogetherFrame()

    @Serializable
    @SerialName("update_control_mode")
    data class UpdateControlMode(
        val mode: RoomControlMode,
    ) : ShareTogetherFrame()

    @Serializable
    @SerialName("leave")
    data class Leave(
        val memberId: String,
    ) : ShareTogetherFrame()
}
