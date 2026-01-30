package dto

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import models.CallStatus
import models.CallType
import models.UserStatus

// Auth DTOs
@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val displayName: String
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class AuthResponse(
    val token: String,
    val user: UserResponse
)

// User DTOs
@Serializable
data class UserResponse(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val status: UserStatus,
    val currentCallId: String?,
    val createdAt: Instant
)

@Serializable
data class UpdateUserRequest(
    val displayName: String? = null,
    val avatarUrl: String? = null
)

// Group DTOs
@Serializable
data class CreateGroupRequest(
    val name: String,
    val description: String? = null,
    val memberIds: List<String>
)

@Serializable
data class GroupResponse(
    val id: String,
    val name: String,
    val description: String?,
    val adminId: String,
    val memberIds: List<String>,
    val activeCallId: String?,
    val createdAt: Instant
)

@Serializable
data class AddMemberRequest(
    val userId: String
)

// Message DTOs
@Serializable
data class SendMessageRequest(
    val content: String
)

@Serializable
data class EditMessageRequest(
    val content: String
)

@Serializable
data class MessageResponse(
    val id: String,
    val senderId: String,
    val senderName: String,
    val groupId: String?,
    val dmId: String?,
    val content: String,
    val createdAt: Instant,
    val editedAt: Instant?
)

@Serializable
data class PaginatedMessages(
    val messages: List<MessageResponse>,
    val hasMore: Boolean
)

// DM DTOs
@Serializable
data class CreateDMRequest(
    val recipientId: String
)

@Serializable
data class DMConversationResponse(
    val id: String,
    val participant1Id: String,
    val participant2Id: String,
    val otherUser: UserResponse,
    val activeCallId: String?,
    val createdAt: Instant
)

// Call DTOs
@Serializable
data class InitiateCallRequest(
    val recipientId: String
)

@Serializable
data class StartGroupCallRequest(
    val groupId: String
)

@Serializable
data class CallResponse(
    val id: String,
    val type: CallType,
    val initiatorId: String,
    val groupId: String?,
    val recipientId: String?,
    val participants: List<CallParticipantResponse>,
    val status: CallStatus,
    val startedAt: Instant,
    val endedAt: Instant?
)

@Serializable
data class CallParticipantResponse(
    val userId: String,
    val displayName: String,
    val joinedAt: Instant,
    val isMuted: Boolean
)

// WebSocket message DTOs
@Serializable
data class WSMessage(
    val type: String,
    val data: String
)

@Serializable
data class TypingIndicator(
    val userId: String,
    val userName: String,
    val groupId: String? = null,
    val dmId: String? = null,
    val isTyping: Boolean
)

@Serializable
data class NewMessageNotification(
    val message: MessageResponse
)

@Serializable
data class UserStatusUpdate(
    val userId: String,
    val status: UserStatus
)

@Serializable
data class CallInitiatedNotification(
    val call: CallResponse,
    val callerName: String
)

@Serializable
data class CallStatusUpdate(
    val callId: String,
    val status: CallStatus
)

@Serializable
data class WebRTCSignal(
    val callId: String? = null,
    val fromUserId: String? = null,
    val toUserId: String? = null,
    val signal: String? = null, // JSON stringified SDP or ICE candidate
    val offer: String? = null,
    val answer: String? = null,
    val candidate: String? = null,
    val ended: Boolean? = null  // For call_ended
)

@Serializable
data class ParticipantUpdate(
    val callId: String,
    val userId: String,
    val userName: String,
    val action: String, // "joined", "left", "muted", "unmuted"
    val isMuted: Boolean? = null
)

@Serializable
data class SimpleMessage(
    val message: String
)