package websocket

import dto.*
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
                // Create a properly formatted WebSocket message
                val wsMessage = createWebSocketMessage(message)
                val json = Json.encodeToString(wsMessage)
                println("Sending to user $userId: $json")
                session.send(Frame.Text(json))
            } catch (e: Exception) {
                println("Error sending message to user $userId: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    suspend fun sendToUsers(userIds: List<String>, message: Any) {
        userIds.forEach { userId ->
            sendToUser(userId, message)
        }
    }

    suspend fun broadcast(message: Any) {
        val wsMessage = createWebSocketMessage(message)
        val json = Json.encodeToString(wsMessage)
        println("Broadcasting: $json")

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
        val jsonData = Json.encodeToString(message)
        return when (message) {
            is TypingIndicator -> WSMessage("typing_indicator", jsonData)
            is NewMessageNotification -> WSMessage("new_message", jsonData)
            is UserStatusUpdate -> WSMessage("user_status", jsonData)
            is CallInitiatedNotification -> WSMessage("call_initiated", jsonData)
            is CallStatusUpdate -> WSMessage("call_status", jsonData)
            is WebRTCSignal -> {
                val type = when {
                    message.signal.contains("\"type\":\"offer\"") -> "webrtc_offer"
                    message.signal.contains("\"type\":\"answer\"") -> "webrtc_answer"
                    else -> "ice_candidate"
                }
                WSMessage(type, jsonData)
            }
            is ParticipantUpdate -> WSMessage("participant_update", jsonData)
            else -> {
                println("Unknown message type: ${message::class.simpleName}")
                WSMessage("unknown", jsonData)
            }
        }
    }
}

@kotlinx.serialization.Serializable
data class WSMessage(
    val type: String,
    val data: String
)