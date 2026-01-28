package routes

import auth.UserPrincipal
import dto.UpdateUserRequest
import models.User
import service.UserService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.userRoutes(userService: UserService) {
    route("/users") {
        authenticate {
            get("/me") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                call.respond(userService.toResponse(user))
            }
            
            put("/me") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@put call.respond(HttpStatusCode.Unauthorized)
                val request = call.receive<UpdateUserRequest>()
                
                val updated = userService.updateUser(user.id, request.displayName, request.avatarUrl)
                if (updated == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
                    return@put
                }
                
                call.respond(userService.toResponse(updated))
            }
            
            get("/{id}") {
                val userId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing user ID"))
                    return@get
                }
                
                val user = userService.findById(userId)
                if (user == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
                    return@get
                }
                
                call.respond(userService.toResponse(user))
            }
        }
    }
}
