package io.github.aedev.flow.sharetogether.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HearingDisabled
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.sharetogether.model.RoomChatMessage
import io.github.aedev.flow.sharetogether.model.RoomControlMode
import io.github.aedev.flow.sharetogether.model.RoomMember
import io.github.aedev.flow.sharetogether.session.ShareTogetherManager
import io.github.aedev.flow.sharetogether.session.ShareTogetherSession

private val ReactionEmojis = listOf("❤️", "🔥", "👏", "🚀", "😂")

/**
 * Full Share Together Room sheet: Room setup, participant list, voice controls, and live chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTogetherSheet(
    onDismiss: () -> Unit,
    onRequireProfile: () -> Unit,
    hasProfileName: Boolean,
    userName: String,
    avatarId: Int,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val session by ShareTogetherManager.session.collectAsStateWithLifecycle()
    val members by ShareTogetherManager.members.collectAsStateWithLifecycle()
    val messages by ShareTogetherManager.chatMessages.collectAsStateWithLifecycle()
    val isMicMuted by ShareTogetherManager.isMicMuted.collectAsStateWithLifecycle()
    val isDeafened by ShareTogetherManager.isDeafened.collectAsStateWithLifecycle()
    val isConnecting by ShareTogetherManager.isConnecting.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var joinCodeInput by remember { mutableStateOf("") }
    var chatInput by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            val currentSession = session
            if (currentSession == null) {
                // Not in room: Create or Join
                UnjoinedRoomContent(
                    hasProfileName = hasProfileName,
                    userName = userName,
                    avatarId = avatarId,
                    isConnecting = isConnecting,
                    joinCodeInput = joinCodeInput,
                    onJoinCodeChange = { joinCodeInput = it },
                    onRequireProfile = onRequireProfile,
                    onCreateRoom = {
                        ShareTogetherManager.createRoom(
                            context = context,
                            hostName = userName,
                            avatarId = avatarId,
                        )
                    },
                    onJoinRoom = { code ->
                        ShareTogetherManager.joinRoom(
                            context = context,
                            roomCodeOrAddress = code,
                            memberName = userName,
                            avatarId = avatarId,
                        )
                    },
                )
            } else {
                // Active Room Session
                ActiveRoomHeader(
                    session = currentSession,
                    onCopyCode = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val copyText = if (currentSession.hostAddress.isNotBlank()) {
                            "${currentSession.roomCode}@${currentSession.hostAddress}"
                        } else {
                            currentSession.roomCode
                        }
                        clipboard.setPrimaryClip(ClipData.newPlainText("Share Together Code", copyText))
                        Toast.makeText(context, "${context.getString(R.string.share_together_copied)}: $copyText", Toast.LENGTH_SHORT).show()
                    },
                    onLeave = {
                        ShareTogetherManager.leaveRoom()
                    },
                )

                Spacer(Modifier.height(12.dp))

                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("${stringResource(R.string.share_together_members)} (${members.size})") },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(R.string.share_together_chat)) },
                    )
                }

                Spacer(Modifier.height(12.dp))

                when (selectedTab) {
                    0 -> {
                        RoomMembersTab(
                            session = currentSession,
                            members = members,
                            isMicMuted = isMicMuted,
                            isDeafened = isDeafened,
                            onToggleMic = { ShareTogetherManager.toggleMicMute() },
                            onToggleDeafen = { ShareTogetherManager.toggleDeafen() },
                            onToggleControlMode = {
                                val next =
                                    if (currentSession.controlMode == RoomControlMode.PARTY) {
                                        RoomControlMode.HOST_DJ
                                    } else {
                                        RoomControlMode.PARTY
                                    }
                                ShareTogetherManager.setControlMode(next)
                            },
                        )
                    }
                    1 -> {
                        RoomChatTab(
                            messages = messages,
                            chatInput = chatInput,
                            onChatInputChange = { chatInput = it },
                            onSendChat = {
                                ShareTogetherManager.sendChatMessage(chatInput)
                                chatInput = ""
                            },
                            onSendReaction = { emoji ->
                                ShareTogetherManager.sendEmojiReaction(emoji)
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun UnjoinedRoomContent(
    hasProfileName: Boolean,
    userName: String,
    avatarId: Int,
    isConnecting: Boolean,
    joinCodeInput: String,
    onJoinCodeChange: (String) -> Unit,
    onRequireProfile: () -> Unit,
    onCreateRoom: () -> Unit,
    onJoinRoom: (String) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.share_together_title),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = colorScheme.onSurface,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.share_together_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        // Create Room Button
        Button(
            onClick = {
                if (!hasProfileName || userName.isBlank()) {
                    onRequireProfile()
                } else {
                    onCreateRoom()
                }
            },
            enabled = !isConnecting,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(stringResource(R.string.share_together_create_room), style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f).height(1.dp).background(colorScheme.outlineVariant))
            Text("  OR  ", style = MaterialTheme.typography.labelSmall, color = colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f).height(1.dp).background(colorScheme.outlineVariant))
        }

        Spacer(Modifier.height(20.dp))

        // Join Room by Code
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = joinCodeInput,
                onValueChange = onJoinCodeChange,
                placeholder = { Text(stringResource(R.string.share_together_enter_code)) },
                singleLine = true,
                enabled = !isConnecting,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f),
            )

            FilledTonalButton(
                onClick = {
                    if (!hasProfileName || userName.isBlank()) {
                        onRequireProfile()
                    } else if (joinCodeInput.isNotBlank()) {
                        onJoinRoom(joinCodeInput.trim())
                    }
                },
                enabled = joinCodeInput.isNotBlank() && !isConnecting,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.height(52.dp),
            ) {
                Text(stringResource(R.string.share_together_join_room))
            }
        }

        if (isConnecting) {
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Connecting to room on Wi-Fi / Hotspot...",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ActiveRoomHeader(
    session: ShareTogetherSession,
    onCopyCode: () -> Unit,
    onLeave: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = stringResource(R.string.share_together_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = colorScheme.onSurface,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onCopyCode),
            ) {
                Text(
                    text = "${stringResource(R.string.share_together_room_code)}: ${session.roomCode}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (session.hostAddress.isNotBlank()) {
                Text(
                    text = "LAN / Wi-Fi: ${session.hostAddress}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }

        IconButton(onClick = onLeave) {
            Icon(
                imageVector = Icons.Default.CallEnd,
                contentDescription = stringResource(R.string.share_together_leave_room),
                tint = colorScheme.error,
            )
        }
    }
}

@Composable
private fun RoomMembersTab(
    session: ShareTogetherSession,
    members: List<RoomMember>,
    isMicMuted: Boolean,
    isDeafened: Boolean,
    onToggleMic: () -> Unit,
    onToggleDeafen: () -> Unit,
    onToggleControlMode: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = Modifier.fillMaxWidth()) {
        // Voice Action Bar
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onToggleMic) {
                        Icon(
                            imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = null,
                            tint = if (isMicMuted) colorScheme.outline else colorScheme.primary,
                        )
                    }
                    Text(
                        text = if (isMicMuted) stringResource(R.string.share_together_mic_unmute) else stringResource(R.string.share_together_mic_mute),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onToggleDeafen) {
                        Icon(
                            imageVector = if (isDeafened) Icons.Default.HearingDisabled else Icons.Default.Hearing,
                            contentDescription = null,
                            tint = if (isDeafened) colorScheme.error else colorScheme.primary,
                        )
                    }
                    Text(
                        text = if (isDeafened) stringResource(R.string.share_together_undeafen) else stringResource(R.string.share_together_deafen),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Members List
        LazyColumn(
            modifier = Modifier.fillMaxWidth().height(260.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(members, key = { it.id }) { member ->
                MemberRow(member = member)
            }
        }
    }
}

@Composable
private fun MemberRow(member: RoomMember) {
    val colorScheme = MaterialTheme.colorScheme
    val avatarColor = ShareTogetherAvatarColors.getOrElse(member.avatarId) { ShareTogetherAvatarColors[0] }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar with speaking halo
            Box(
                modifier =
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(avatarColor)
                        .border(
                            width = if (member.isSpeaking) 2.dp else 0.dp,
                            color = if (member.isSpeaking) Color(0xFF10B981) else Color.Transparent,
                            shape = CircleShape,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = member.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = member.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colorScheme.onSurface,
                    )
                    if (member.isHost) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = stringResource(R.string.share_together_host),
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Text(
                    text = if (member.isSpeaking) "Speaking..." else if (member.isMuted) "Muted" else "Listening",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (member.isSpeaking) Color(0xFF10B981) else colorScheme.onSurfaceVariant,
                )
            }

            Icon(
                imageVector = if (member.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = null,
                tint = if (member.isMuted) colorScheme.outline else colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun RoomChatTab(
    messages: List<RoomChatMessage>,
    chatInput: String,
    onChatInputChange: (String) -> Unit,
    onSendChat: () -> Unit,
    onSendReaction: (String) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = Modifier.fillMaxWidth()) {
        // Quick reaction bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ReactionEmojis.forEach { emoji ->
                Surface(
                    shape = CircleShape,
                    color = colorScheme.surfaceContainerHigh,
                    modifier = Modifier.clickable { onSendReaction(emoji) },
                ) {
                    Text(
                        text = emoji,
                        fontSize = 22.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Chat message history
        LazyColumn(
            modifier = Modifier.fillMaxWidth().height(200.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                ChatBubble(msg = msg)
            }
        }

        Spacer(Modifier.height(8.dp))

        // Message input
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = chatInput,
                onValueChange = onChatInputChange,
                placeholder = { Text(stringResource(R.string.share_together_chat_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            )

            IconButton(
                onClick = onSendChat,
                enabled = chatInput.isNotBlank(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = if (chatInput.isNotBlank()) colorScheme.primary else colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: RoomChatMessage) {
    val colorScheme = MaterialTheme.colorScheme

    if (msg.isSystem) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = msg.text,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    } else {
        val avatarColor = ShareTogetherAvatarColors.getOrElse(msg.senderAvatarId) { ShareTogetherAvatarColors[0] }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier.size(24.dp).clip(CircleShape).background(avatarColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = msg.senderName.take(1).uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                }

                Spacer(Modifier.width(8.dp))

                Column {
                    Text(
                        text = msg.senderName,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = avatarColor,
                    )
                    Text(
                        text = msg.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
