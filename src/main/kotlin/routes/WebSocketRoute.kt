package routes

import auth.JWTConfig
import models.UserStatus
import service.CallService
import service.UserService
import websocket.WebSocketConnectionManager
import dto.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

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
            e.printStackTrace()
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
        println("=== RAW WebSocket message ===")
        println("From user: $userId")
        println("Raw text: $text")

        // Parse the outer message
        val outerMessage = Json.decodeFromString<WSMessage>(text)
        println("Message type: ${outerMessage.type}")

        when (outerMessage.type) {
            "typing_indicator" -> {
                val indicator = Json.decodeFromString<TypingIndicator>(outerMessage.data)
                println("Typing indicator: $indicator")
                wsManager.broadcast(indicator)
            }

            "webrtc_offer", "webrtc_answer", "ice_candidate" -> {
                // CRITICAL FIX: Parse the signal and forward it WITHOUT re-wrapping
                val signal = Json.decodeFromString<WebRTCSignal>(outerMessage.data)
                println("=== WebRTC Signal ===")
                println("Type: ${outerMessage.type}")
                println("From: ${signal.fromUserId}")
                println("To: ${signal.toUserId}")
                println("Call ID: ${signal.callId}")
                println("Signal (first 100 chars): ${signal.signal.take(100)}")
                
                // FIXED: Send the ORIGINAL message type and data directly
                // This avoids the triple-encoding issue
                wsManager.sendToUserDirect(
                    userId = signal.toUserId,
                    type = outerMessage.type,
                    data = outerMessage.data
                )
                
                println("Signal forwarded to ${signal.toUserId}")
            }

            "mute_toggle" -> {
                val data = Json.parseToJsonElement(outerMessage.data).jsonObject
                val callId = data["callId"]?.toString()?.removeSurrounding("\"")
                val isMuted = data["isMuted"]?.toString()?.toBoolean() ?: false

                println("Mute toggle: callId=$callId, isMuted=$isMuted")

                if (callId != null) {
                    callService.toggleMute(callId, userId, isMuted)
                }
            }

            "ping" -> {
                println("Received ping from client")
                wsManager.sendToUser(userId, "pong")
            }

            else -> {
                println("Unknown WebSocket message type: ${outerMessage.type}")
            }
        }
        println("=== End of message processing ===\n")
    } catch (e: Exception) {
        println("=== ERROR handling WebSocket message ===")
        println("Error: ${e.message}")
        println("Stack trace:")
        e.printStackTrace()
        println("=== End error ===")
    }
}
