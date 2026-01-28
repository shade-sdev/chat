package routes

import auth.JWTConfig
import models.UserStatus
import service.CallService
import service.UserService
import websocket.WebSocketConnectionManager
import dto.TypingIndicator
import dto.WSMessage
import dto.WebRTCSignal
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

fun Route.websocketRoute(
    wsManager: WebSocketConnectionManager,
    userService: UserService,
    callService: CallService
) {
    webSocket("/ws") {
        var userId: String? = null

        try {
            // Authenticate via query parameter token
            val token = call.request.queryParameters["token"]
            userId = token?.let { JWTConfig.verifyToken(it) }

            if (userId == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                return@webSocket
            }

            // Register connection
            wsManager.addConnection(userId, this)
            userService.updateStatus(userId, UserStatus.ONLINE)

            println("User $userId connected via WebSocket")

            // Handle incoming messages
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    handleWebSocketMessage(text, userId, wsManager, callService)
                }
            }
        } catch (e: Exception) {
            println("WebSocket error for user $userId: ${e.message}")
        } finally {
            // Clean up on disconnect
            if (userId != null) {
                wsManager.removeConnection(userId)
                userService.updateStatus(userId, UserStatus.OFFLINE)
                println("User $userId disconnected from WebSocket")
            }
        }
    }
}

private suspend fun handleWebSocketMessage(
    text: String,
    userId: String,
    wsManager: WebSocketConnectionManager,
    callService: CallService
) {
    try {
        println("Received WebSocket message: $text")

        // Parse the message - frontend sends {type, data} structure
        val jsonObject = Json.parseToJsonElement(text) as JsonObject
        val type = jsonObject["type"]?.toString()?.removeSurrounding("\"") ?: return
        val dataString = jsonObject["data"]?.toString()?.removeSurrounding("\"") ?: return

        when (type) {
            "typing_indicator" -> {
                val indicator = Json.decodeFromString<TypingIndicator>(dataString)
                // Broadcast to relevant users
                if (indicator.groupId != null) {
                    // For group typing indicator
                    // In production, you'd filter to group members only
                    wsManager.broadcast(TypingIndicator(
                        userId = indicator.userId,
                        userName = indicator.userName,
                        groupId = indicator.groupId,
                        dmId = null,
                        isTyping = indicator.isTyping
                    ))
                } else if (indicator.dmId != null) {
                    // For DM typing indicator - send only to other participant
                    // In production, you'd need to look up the other participant
                    wsManager.broadcast(TypingIndicator(
                        userId = indicator.userId,
                        userName = indicator.userName,
                        groupId = null,
                        dmId = indicator.dmId,
                        isTyping = indicator.isTyping
                    ))
                }
            }

            "webrtc_offer", "webrtc_answer", "ice_candidate" -> {
                val signal = Json.decodeFromString<WebRTCSignal>(dataString)
                // Forward signal to the target user
                wsManager.sendToUser(signal.toUserId, signal)
            }

            "mute_toggle" -> {
                val data = Json.parseToJsonElement(dataString).let {
                    it as JsonObject
                }
                val callId = data["callId"]?.toString()?.removeSurrounding("\"")
                val isMuted = data["isMuted"]?.toString()?.toBoolean() ?: false

                if (callId != null) {
                    callService.toggleMute(callId, userId, isMuted)
                }
            }

            else -> {
                println("Unknown WebSocket message type: $type")
            }
        }
    } catch (e: Exception) {
        println("Error handling WebSocket message: ${e.message}")
        e.printStackTrace()
    }
}