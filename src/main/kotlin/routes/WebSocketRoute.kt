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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

        // Parse as JSON object first to check structure
        val json = Json.parseToJsonElement(text).jsonObject
        val type = json["type"]?.jsonPrimitive?.content

        if (type == null) {
            println("No type field in message")
            return
        }

        println("Message type: $type")

        when (type) {
            "ping" -> {
                println("Received ping from client")
                // Send pong back
                val pongMessage = mapOf("type" to "pong")
                val jsonResponse = Json.encodeToString(pongMessage)
                wsManager.getConnection(userId)?.send(Frame.Text(jsonResponse))
            }

            "typing_indicator" -> {
                val data = json["data"]?.toString() ?: return
                val indicator = Json.decodeFromString<TypingIndicator>(data)
                println("Typing indicator: $indicator")
                wsManager.broadcast(indicator)
            }

            "webrtc_offer", "webrtc_answer", "ice_candidate", "call_ended" -> {
                val data = json["data"]?.toString() ?: return
                println("Forwarding WebRTC signal type: $type")

                try {
                    // Try to parse as WebRTCSignal
                    val signal = Json.decodeFromString<WebRTCSignal>(data)

                    // Extract recipientId from signal
                    val recipientId = signal.toUserId

                    if (recipientId == null) {
                        println("Error: No recipientId in WebRTC signal")
                        return
                    }

                    println("From ${signal.fromUserId} to $recipientId")

                    // Send directly to target user using the new method
                    wsManager.sendToUserDirect(
                        userId = recipientId,
                        type = type,
                        data = data  // Pass the original signal data
                    )
                } catch (e: Exception) {
                    println("Error parsing WebRTCSignal: ${e.message}")
                    // Try alternative parsing
                    try {
                        val dataObj = Json.parseToJsonElement(data).jsonObject
                        val recipientId = dataObj["toUserId"]?.jsonPrimitive?.content ?:
                        dataObj["recipientId"]?.jsonPrimitive?.content
                        val fromUserId = dataObj["fromUserId"]?.jsonPrimitive?.content ?: userId

                        if (recipientId != null) {
                            println("Alternative parsing: From $fromUserId to $recipientId")

                            wsManager.sendToUserDirect(
                                userId = recipientId,
                                type = type,
                                data = data
                            )
                        } else {
                            println("Error: Could not extract recipientId from signal data")
                        }
                    } catch (e2: Exception) {
                        println("Failed alternative parsing: ${e2.message}")
                    }
                }
            }

            "mute_toggle" -> {
                val data = json["data"]?.toString() ?: return
                val dataJson = Json.parseToJsonElement(data).jsonObject
                val callId = dataJson["callId"]?.jsonPrimitive?.content
                val isMuted = dataJson["isMuted"]?.jsonPrimitive?.boolean ?: false

                println("Mute toggle: callId=$callId, isMuted=$isMuted")

                if (callId != null) {
                    callService.toggleMute(callId, userId, isMuted)
                }
            }

            else -> {
                println("Unknown WebSocket message type: $type")
                // Try to parse as WSMessage for other types
                try {
                    val outerMessage = Json.decodeFromString<WSMessage>(text)
                    println("Parsed as WSMessage with data: ${outerMessage.data}")
                } catch (e: Exception) {
                    println("Cannot parse as WSMessage: ${e.message}")
                }
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

