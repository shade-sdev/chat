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
        try {
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
        } catch (e: Exception) {
            println("Error creating WebSocket message for broadcast: ${e.message}")
            e.printStackTrace()
        }
    }

    fun getConnectedUserIds(): List<String> {
        return connections.keys.toList()
    }

    private fun createWebSocketMessage(message: Any): WSMessage {
        return when (message) {
            is TypingIndicator -> {
                val jsonData = Json.encodeToString(message)
                WSMessage("typing_indicator", jsonData)
            }
            is NewMessageNotification -> {
                val jsonData = Json.encodeToString(message)
                WSMessage("new_message", jsonData)
            }
            is UserStatusUpdate -> {
                val jsonData = Json.encodeToString(message)
                WSMessage("user_status", jsonData)
            }
            is CallInitiatedNotification -> {
                val jsonData = Json.encodeToString(message)
                WSMessage("call_initiated", jsonData)
            }
            is CallStatusUpdate -> {
                val jsonData = Json.encodeToString(message)
                WSMessage("call_status", jsonData)
            }
            is WebRTCSignal -> {
                val jsonData = Json.encodeToString(message)
                val type = when {
                    message.signal.contains("\"type\":\"offer\"") -> "webrtc_offer"
                    message.signal.contains("\"type\":\"answer\"") -> "webrtc_answer"
                    else -> "ice_candidate"
                }
                WSMessage(type, jsonData)
            }
            is ParticipantUpdate -> {
                val jsonData = Json.encodeToString(message)
                WSMessage("participant_update", jsonData)
            }
            else -> {
                println("Unknown message type: ${message::class.simpleName}")
                // Create a simple string message instead of trying to serialize Any
                WSMessage("unknown", "\"${message.toString()}\"")
            }
        }
    }
}

@kotlinx.serialization.Serializable
data class WSMessage(
    val type: String,
    val data: String
)