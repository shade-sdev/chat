package websocket

import dto.CallInitiatedNotification
import dto.CallStatusUpdate
import dto.NewMessageNotification
import dto.ParticipantUpdate
import dto.TypingIndicator
import dto.UserStatusUpdate
import dto.WebRTCSignal
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

class WebSocketConnectionManager {
    private val connections = ConcurrentHashMap<String, DefaultWebSocketSession>()

    fun addConnection(userId: String, session: DefaultWebSocketSession) {
        connections[userId] = session
    }

    fun removeConnection(userId: String) {
        connections.remove(userId)
    }

    fun getConnection(userId: String): DefaultWebSocketSession? {
        return connections[userId]
    }

    fun isUserConnected(userId: String): Boolean {
        return connections.containsKey(userId)
    }

    suspend fun sendToUser(userId: String, message: Any) {
        val session = connections[userId]
        if (session != null && !session.outgoing.isClosedForSend) {
            try {
                // Create a properly typed wrapper message
                val wrapper = createWebSocketMessage(message)
                val json = Json.encodeToString(wrapper)
                session.send(Frame.Text(json))
            } catch (e: Exception) {
                println("Error sending message to user $userId: ${e.message}")
            }
        }
    }

    suspend fun sendToUsers(userIds: List<String>, message: Any) {
        userIds.forEach { userId ->
            sendToUser(userId, message)
        }
    }

    suspend fun broadcast(message: Any) {
        val wrapper = createWebSocketMessage(message)
        val json = Json.encodeToString(wrapper)

        connections.values.forEach { session ->
            if (!session.outgoing.isClosedForSend) {
                try {
                    session.send(Frame.Text(json))
                } catch (e: Exception) {
                    println("Error broadcasting message: ${e.message}")
                }
            }
        }
    }

    fun getConnectedUserIds(): List<String> {
        return connections.keys.toList()
    }

    private fun createWebSocketMessage(message: Any): WSMessage {
        return when (message) {
            is TypingIndicator -> WSMessage("typing_indicator", Json.encodeToString(message))
            is NewMessageNotification -> WSMessage("new_message", Json.encodeToString(message))
            is UserStatusUpdate -> WSMessage("user_status", Json.encodeToString(message))
            is CallInitiatedNotification -> WSMessage("call_initiated", Json.encodeToString(message))
            is CallStatusUpdate -> WSMessage("call_status", Json.encodeToString(message))
            is WebRTCSignal -> {
                val type = when {
                    message.signal.contains("\"type\":\"offer\"") -> "webrtc_offer"
                    message.signal.contains("\"type\":\"answer\"") -> "webrtc_answer"
                    else -> "ice_candidate"
                }
                WSMessage(type, Json.encodeToString(message))
            }
            is ParticipantUpdate -> WSMessage("participant_update", Json.encodeToString(message))
            else -> {
                println("Unknown message type: ${message::class.simpleName}")
                WSMessage("unknown", Json.encodeToString(message.toString()))
            }
        }
    }
}

// Add this data class to the same file
@kotlinx.serialization.Serializable
data class WSMessage(
    val type: String,
    val data: String
)
