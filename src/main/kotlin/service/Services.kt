package service

import auth.hashPassword
import auth.verifyPassword
import websocket.WebSocketConnectionManager
import models.Call
import models.CallParticipant
import models.CallStatus
import models.CallType
import models.DirectMessageConversation
import models.Group
import models.Message
import models.User
import models.UserStatus
import repository.CallRepository
import repository.DMRepository
import repository.GroupRepository
import repository.MessageRepository
import repository.UserRepository
import dto.CallInitiatedNotification
import dto.CallParticipantResponse
import dto.CallResponse
import dto.CallStatusUpdate
import dto.DMConversationResponse
import dto.GroupResponse
import dto.MessageResponse
import dto.NewMessageNotification
import dto.PaginatedMessages
import dto.ParticipantUpdate
import dto.UserResponse
import dto.UserStatusUpdate
import kotlinx.datetime.Clock
import java.util.*

class UserService(
    private val userRepository: UserRepository,
    private val wsManager: WebSocketConnectionManager
) {
    suspend fun register(username: String, password: String, displayName: String): User? {
        if (userRepository.findByUsername(username) != null) {
            return null
        }
        
        val user = User(
            id = UUID.randomUUID().toString(),
            username = username,
            displayName = displayName,
            passwordHash = hashPassword(password),
            createdAt = Clock.System.now()
        )
        
        return userRepository.save(user)
    }
    
    suspend fun authenticate(username: String, password: String): User? {
        val user = userRepository.findByUsername(username) ?: return null
        return if (verifyPassword(password, user.passwordHash)) user else null
    }
    
    suspend fun findById(id: String): User? = userRepository.findById(id)
    
    suspend fun updateUser(id: String, displayName: String?, avatarUrl: String?): User? {
        return userRepository.update(id) { user ->
            user.copy(
                displayName = displayName ?: user.displayName,
                avatarUrl = avatarUrl ?: user.avatarUrl
            )
        }
    }
    
    suspend fun updateStatus(userId: String, status: UserStatus) {
        userRepository.update(userId) { it.copy(status = status) }
        
        // Broadcast status update
        wsManager.broadcast(UserStatusUpdate(userId, status))
    }
    
    suspend fun setCurrentCall(userId: String, callId: String?) {
        userRepository.update(userId) { it.copy(currentCallId = callId) }
    }
    
    fun toResponse(user: User) = UserResponse(
        id = user.id,
        username = user.username,
        displayName = user.displayName,
        avatarUrl = user.avatarUrl,
        status = user.status,
        currentCallId = user.currentCallId,
        createdAt = user.createdAt
    )
}

class GroupService(
    private val groupRepository: GroupRepository,
    private val userRepository: UserRepository
) {
    suspend fun createGroup(name: String, description: String?, adminId: String, memberIds: List<String>): Group {
        val allMembers = (memberIds + adminId).distinct().toMutableList()
        
        val group = Group(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            adminId = adminId,
            memberIds = allMembers,
            createdAt = Clock.System.now()
        )
        
        return groupRepository.save(group)
    }
    
    suspend fun findById(id: String): Group? = groupRepository.findById(id)
    
    suspend fun findByUserId(userId: String): List<Group> = groupRepository.findByUserId(userId)
    
    suspend fun addMember(groupId: String, userId: String): Group? {
        return groupRepository.update(groupId) { group ->
            if (userId !in group.memberIds) {
                group.memberIds.add(userId)
            }
            group
        }
    }
    
    suspend fun removeMember(groupId: String, userId: String): Group? {
        return groupRepository.update(groupId) { group ->
            group.memberIds.remove(userId)
            group
        }
    }
    
    suspend fun deleteGroup(groupId: String): Boolean {
        return groupRepository.delete(groupId)
    }
    
    suspend fun setActiveCall(groupId: String, callId: String?) {
        groupRepository.update(groupId) { it.copy(activeCallId = callId) }
    }
    
    fun toResponse(group: Group) = GroupResponse(
        id = group.id,
        name = group.name,
        description = group.description,
        adminId = group.adminId,
        memberIds = group.memberIds,
        activeCallId = group.activeCallId,
        createdAt = group.createdAt
    )
}

class MessageService(
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    private val wsManager: WebSocketConnectionManager
) {
    suspend fun sendMessage(senderId: String, groupId: String?, dmId: String?, content: String): Message {
        val message = Message(
            id = UUID.randomUUID().toString(),
            senderId = senderId,
            groupId = groupId,
            dmId = dmId,
            content = content,
            createdAt = Clock.System.now()
        )
        
        messageRepository.save(message)
        
        // Broadcast new message
        val sender = userRepository.findById(senderId)
        val response = toResponse(message, sender?.displayName ?: "Unknown")
        wsManager.broadcast(NewMessageNotification(response))
        
        return message
    }
    
    suspend fun getGroupMessages(groupId: String, limit: Int = 50, offset: Int = 0): PaginatedMessages {
        val messages = messageRepository.findByGroupId(groupId, limit + 1, offset)
        val hasMore = messages.size > limit
        val messageResponses = messages.take(limit).map { msg ->
            val sender = userRepository.findById(msg.senderId)
            toResponse(msg, sender?.displayName ?: "Unknown")
        }
        return PaginatedMessages(messageResponses, hasMore)
    }
    
    suspend fun getDMMessages(dmId: String, limit: Int = 50, offset: Int = 0): PaginatedMessages {
        val messages = messageRepository.findByDmId(dmId, limit + 1, offset)
        val hasMore = messages.size > limit
        val messageResponses = messages.take(limit).map { msg ->
            val sender = userRepository.findById(msg.senderId)
            toResponse(msg, sender?.displayName ?: "Unknown")
        }
        return PaginatedMessages(messageResponses, hasMore)
    }
    
    suspend fun editMessage(messageId: String, userId: String, content: String): Message? {
        val message = messageRepository.findById(messageId) ?: return null
        if (message.senderId != userId) return null
        
        return messageRepository.update(messageId) {
            it.copy(content = content, editedAt = Clock.System.now())
        }
    }
    
    suspend fun deleteMessage(messageId: String, userId: String): Boolean {
        val message = messageRepository.findById(messageId) ?: return false
        if (message.senderId != userId) return false
        
        return messageRepository.delete(messageId)
    }
    
    private fun toResponse(message: Message, senderName: String) = MessageResponse(
        id = message.id,
        senderId = message.senderId,
        senderName = senderName,
        groupId = message.groupId,
        dmId = message.dmId,
        content = message.content,
        createdAt = message.createdAt,
        editedAt = message.editedAt
    )
}

class DMService(
    private val dmRepository: DMRepository,
    private val userRepository: UserRepository
) {
    suspend fun createOrGetConversation(user1Id: String, user2Id: String): DirectMessageConversation {
        val existing = dmRepository.findByParticipants(user1Id, user2Id)
        if (existing != null) return existing
        
        val dm = DirectMessageConversation(
            id = UUID.randomUUID().toString(),
            participant1Id = user1Id,
            participant2Id = user2Id,
            createdAt = Clock.System.now()
        )
        
        return dmRepository.save(dm)
    }
    
    suspend fun findById(id: String): DirectMessageConversation? = dmRepository.findById(id)
    
    suspend fun findByUserId(userId: String): List<DirectMessageConversation> = dmRepository.findByUserId(userId)
    
    suspend fun setActiveCall(dmId: String, callId: String?) {
        dmRepository.update(dmId) { it.copy(activeCallId = callId) }
    }
    
    suspend fun toResponse(dm: DirectMessageConversation, currentUserId: String): DMConversationResponse {
        val otherUserId = if (dm.participant1Id == currentUserId) dm.participant2Id else dm.participant1Id
        val otherUser = userRepository.findById(otherUserId)!!
        
        return DMConversationResponse(
            id = dm.id,
            participant1Id = dm.participant1Id,
            participant2Id = dm.participant2Id,
            otherUser = UserResponse(
                id = otherUser.id,
                username = otherUser.username,
                displayName = otherUser.displayName,
                avatarUrl = otherUser.avatarUrl,
                status = otherUser.status,
                currentCallId = otherUser.currentCallId,
                createdAt = otherUser.createdAt
            ),
            activeCallId = dm.activeCallId,
            createdAt = dm.createdAt
        )
    }
}

class CallService(
    private val callRepository: CallRepository,
    private val userRepository: UserRepository,
    private val groupService: GroupService,
    private val dmService: DMService,
    private val userService: UserService,
    private val wsManager: WebSocketConnectionManager
) {
    suspend fun initiateDirectCall(callerId: String, recipientId: String): Call? {
        // Check if users are already in a call
        if (callRepository.findActiveByUserId(callerId) != null ||
            callRepository.findActiveByUserId(recipientId) != null) {
            return null
        }
        
        val call = Call(
            id = UUID.randomUUID().toString(),
            type = CallType.DIRECT,
            initiatorId = callerId,
            recipientId = recipientId,
            participants = mutableListOf(
                CallParticipant(callerId, Clock.System.now())
            ),
            status = CallStatus.RINGING,
            startedAt = Clock.System.now()
        )
        
        callRepository.save(call)
        
        // Update user statuses
        userService.updateStatus(callerId, UserStatus.IN_CALL)
        userService.setCurrentCall(callerId, call.id)
        
        // Update DM conversation
        val dm = dmService.createOrGetConversation(callerId, recipientId)
        dmService.setActiveCall(dm.id, call.id)
        
        // Notify recipient
        val caller = userRepository.findById(callerId)!!
        val callResponse = toResponse(call)
        wsManager.sendToUser(recipientId, CallInitiatedNotification(callResponse, caller.displayName))
        
        return call
    }
    
    suspend fun acceptCall(callId: String, userId: String): Call? {
        val call = callRepository.findById(callId) ?: return null
        if (call.status != CallStatus.RINGING) return null
        
        val updated = callRepository.update(callId) {
            it.participants.add(CallParticipant(userId, Clock.System.now()))
            it.copy(status = CallStatus.ACTIVE)
        } ?: return null
        
        // Update user status
        userService.updateStatus(userId, UserStatus.IN_CALL)
        userService.setCurrentCall(userId, callId)
        
        // Notify all participants
        val callResponse = toResponse(updated)
        wsManager.sendToUsers(
            updated.participants.map { it.userId },
            CallStatusUpdate(callId, CallStatus.ACTIVE)
        )
        
        return updated
    }
    
    suspend fun rejectCall(callId: String, userId: String): Call? {
        val call = callRepository.findById(callId) ?: return null
        if (call.status != CallStatus.RINGING) return null
        
        val updated = callRepository.update(callId) {
            it.copy(status = CallStatus.ENDED, endedAt = Clock.System.now())
        } ?: return null
        
        // Update initiator status
        userService.updateStatus(call.initiatorId, UserStatus.ONLINE)
        userService.setCurrentCall(call.initiatorId, null)
        
        // Clear DM active call
        if (call.type == CallType.DIRECT && call.recipientId != null) {
            val dm = dmService.createOrGetConversation(call.initiatorId, call.recipientId)
            dmService.setActiveCall(dm.id, null)
        }
        
        // Notify initiator
        wsManager.sendToUser(call.initiatorId, CallStatusUpdate(callId, CallStatus.ENDED))
        
        return updated
    }
    
    suspend fun endCall(callId: String, userId: String): Call? {
        val call = callRepository.findById(callId) ?: return null
        if (call.status == CallStatus.ENDED) return null
        
        val updated = callRepository.update(callId) {
            it.copy(status = CallStatus.ENDED, endedAt = Clock.System.now())
        } ?: return null
        
        // Update all participants' statuses
        updated.participants.forEach { participant ->
            userService.updateStatus(participant.userId, UserStatus.ONLINE)
            userService.setCurrentCall(participant.userId, null)
        }
        
        // Clear active call references
        if (call.type == CallType.DIRECT && call.recipientId != null) {
            val dm = dmService.createOrGetConversation(call.initiatorId, call.recipientId)
            dmService.setActiveCall(dm.id, null)
        } else if (call.type == CallType.GROUP && call.groupId != null) {
            groupService.setActiveCall(call.groupId, null)
        }
        
        // Notify all participants
        wsManager.sendToUsers(
            updated.participants.map { it.userId },
            CallStatusUpdate(callId, CallStatus.ENDED)
        )
        
        return updated
    }
    
    suspend fun startGroupCall(groupId: String, userId: String): Call? {
        val group = groupService.findById(groupId) ?: return null
        if (group.activeCallId != null) return null // Already has an active call
        
        val call = Call(
            id = UUID.randomUUID().toString(),
            type = CallType.GROUP,
            initiatorId = userId,
            groupId = groupId,
            participants = mutableListOf(
                CallParticipant(userId, Clock.System.now())
            ),
            status = CallStatus.ACTIVE,
            startedAt = Clock.System.now()
        )
        
        callRepository.save(call)
        groupService.setActiveCall(groupId, call.id)
        
        // Update initiator status
        userService.updateStatus(userId, UserStatus.IN_CALL)
        userService.setCurrentCall(userId, call.id)
        
        // Notify all group members
        val user = userRepository.findById(userId)!!
        wsManager.sendToUsers(
            group.memberIds.filter { it != userId },
            ParticipantUpdate(call.id, userId, user.displayName, "started_call")
        )
        
        return call
    }
    
    suspend fun joinGroupCall(callId: String, userId: String): Call? {
        val call = callRepository.findById(callId) ?: return null
        if (call.type != CallType.GROUP || call.status != CallStatus.ACTIVE) return null
        
        // Check if already in call
        if (call.participants.any { it.userId == userId }) return call
        
        val updated = callRepository.update(callId) {
            it.participants.add(CallParticipant(userId, Clock.System.now()))
            it
        } ?: return null
        
        // Update user status
        userService.updateStatus(userId, UserStatus.IN_CALL)
        userService.setCurrentCall(userId, callId)
        
        // Notify other participants
        val user = userRepository.findById(userId)!!
        wsManager.sendToUsers(
            updated.participants.filter { it.userId != userId }.map { it.userId },
            ParticipantUpdate(callId, userId, user.displayName, "joined")
        )
        
        return updated
    }
    
    suspend fun leaveGroupCall(callId: String, userId: String): Call? {
        val call = callRepository.findById(callId) ?: return null
        if (call.type != CallType.GROUP) return null
        
        val updated = callRepository.update(callId) {
            it.participants.removeIf { p -> p.userId == userId }
            
            // End call if no participants left
            if (it.participants.isEmpty()) {
                it.copy(status = CallStatus.ENDED, endedAt = Clock.System.now())
            } else {
                it
            }
        } ?: return null
        
        // Update user status
        userService.updateStatus(userId, UserStatus.ONLINE)
        userService.setCurrentCall(userId, null)
        
        // Clear group active call if ended
        if (updated.status == CallStatus.ENDED && call.groupId != null) {
            groupService.setActiveCall(call.groupId, null)
        }
        
        // Notify other participants
        val user = userRepository.findById(userId)!!
        wsManager.sendToUsers(
            updated.participants.map { it.userId },
            ParticipantUpdate(callId, userId, user.displayName, "left")
        )
        
        return updated
    }
    
    suspend fun toggleMute(callId: String, userId: String, isMuted: Boolean): Call? {
        val call = callRepository.findById(callId) ?: return null
        
        val updated = callRepository.update(callId) {
            val participant = it.participants.find { p -> p.userId == userId }
            participant?.isMuted = isMuted
            it
        } ?: return null
        
        // Notify other participants
        val user = userRepository.findById(userId)!!
        wsManager.sendToUsers(
            updated.participants.filter { it.userId != userId }.map { it.userId },
            ParticipantUpdate(callId, userId, user.displayName, if (isMuted) "muted" else "unmuted", isMuted)
        )
        
        return updated
    }
    
    suspend fun findById(callId: String): Call? = callRepository.findById(callId)
    
    suspend fun toResponse(call: Call): CallResponse {
        val participantResponses = call.participants.map { participant ->
            val user = userRepository.findById(participant.userId)!!
            CallParticipantResponse(
                userId = participant.userId,
                displayName = user.displayName,
                joinedAt = participant.joinedAt,
                isMuted = participant.isMuted
            )
        }
        
        return CallResponse(
            id = call.id,
            type = call.type,
            initiatorId = call.initiatorId,
            groupId = call.groupId,
            recipientId = call.recipientId,
            participants = participantResponses,
            status = call.status,
            startedAt = call.startedAt,
            endedAt = call.endedAt
        )
    }
}
