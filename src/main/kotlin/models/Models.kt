package models

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

enum class UserStatus {
    ONLINE, AWAY, OFFLINE, IN_CALL
}

enum class CallType {
    DIRECT, GROUP
}

enum class CallStatus {
    RINGING, ACTIVE, ENDED
}

@Serializable
data class User(
    val id: String,
    val username: String,
    var displayName: String,
    var avatarUrl: String? = null,
    var status: UserStatus = UserStatus.OFFLINE,
    var currentCallId: String? = null,
    val passwordHash: String,
    val createdAt: Instant
)

@Serializable
data class Group(
    val id: String,
    var name: String,
    var description: String? = null,
    val adminId: String,
    val memberIds: MutableList<String>,
    var activeCallId: String? = null,
    val createdAt: Instant
)

@Serializable
data class Message(
    val id: String,
    val senderId: String,
    val groupId: String? = null,
    val dmId: String? = null,
    val content: String,
    val createdAt: Instant,
    var editedAt: Instant? = null
)

@Serializable
data class DirectMessageConversation(
    val id: String,
    val participant1Id: String,
    val participant2Id: String,
    var activeCallId: String? = null,
    val createdAt: Instant
)

@Serializable
data class CallParticipant(
    val userId: String,
    val joinedAt: Instant,
    var isMuted: Boolean = false
)

@Serializable
data class Call(
    val id: String,
    val type: CallType,
    val initiatorId: String,
    val groupId: String? = null, // For group calls
    val recipientId: String? = null, // For direct calls
    val participants: MutableList<CallParticipant>,
    var status: CallStatus,
    val startedAt: Instant,
    var endedAt: Instant? = null
)
