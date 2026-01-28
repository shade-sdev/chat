package routes

import auth.UserPrincipal
import models.User
import service.CallService
import dto.InitiateCallRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.callRoutes(callService: CallService) {
    route("/calls") {
        authenticate {
            post("/initiate") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val request = call.receive<InitiateCallRequest>()
                
                val callObj = callService.initiateDirectCall(user.id, request.recipientId)
                if (callObj == null) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "User already in a call"))
                    return@post
                }
                
                call.respond(HttpStatusCode.Created, callService.toResponse(callObj))
            }
            
            post("/{id}/accept") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val callId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing call ID"))
                    return@post
                }
                
                val callObj = callService.acceptCall(callId, user.id)
                if (callObj == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Cannot accept call"))
                    return@post
                }
                
                call.respond(callService.toResponse(callObj))
            }
            
            post("/{id}/reject") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val callId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing call ID"))
                    return@post
                }
                
                val callObj = callService.rejectCall(callId, user.id)
                if (callObj == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Cannot reject call"))
                    return@post
                }
                
                call.respond(callService.toResponse(callObj))
            }
            
            post("/{id}/end") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val callId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing call ID"))
                    return@post
                }
                
                val callObj = callService.endCall(callId, user.id)
                if (callObj == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Cannot end call"))
                    return@post
                }
                
                call.respond(callService.toResponse(callObj))
            }
            
            get("/{id}") {
                val callId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing call ID"))
                    return@get
                }
                
                val callObj = callService.findById(callId)
                if (callObj == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Call not found"))
                    return@get
                }
                
                call.respond(callService.toResponse(callObj))
            }
        }
    }
    
    route("/groups/{id}/calls") {
        authenticate {
            post("/start") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val groupId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing group ID"))
                    return@post
                }
                
                val callObj = callService.startGroupCall(groupId, user.id)
                if (callObj == null) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "Group already has an active call"))
                    return@post
                }
                
                call.respond(HttpStatusCode.Created, callService.toResponse(callObj))
            }
            
            post("/{callId}/join") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val callId = call.parameters["callId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing call ID"))
                    return@post
                }
                
                val callObj = callService.joinGroupCall(callId, user.id)
                if (callObj == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Cannot join call"))
                    return@post
                }
                
                call.respond(callService.toResponse(callObj))
            }
            
            post("/{callId}/leave") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val callId = call.parameters["callId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing call ID"))
                    return@post
                }
                
                val callObj = callService.leaveGroupCall(callId, user.id)
                if (callObj == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Cannot leave call"))
                    return@post
                }
                
                call.respond(callService.toResponse(callObj))
            }
            
            post("/{callId}/mute") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val callId = call.parameters["callId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing call ID"))
                    return@post
                }
                val isMuted = call.request.queryParameters["muted"]?.toBoolean() ?: true
                
                val callObj = callService.toggleMute(callId, user.id, isMuted)
                if (callObj == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Cannot toggle mute"))
                    return@post
                }
                
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
