import auth.UserPrincipal
import auth.hashPassword
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock.System.now
import kotlinx.serialization.json.Json
import models.User
import repository.*
import routes.*
import service.*
import websocket.WebSocketConnectionManager
import java.time.Duration


fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        CoroutineScope(Dispatchers.IO).launch {
            configureApp()
        }
    }.start(wait = true)
}

suspend fun Application.configureApp() {

    /* ---------------------------------------------------
     * Repositories
     * --------------------------------------------------- */
    val userRepository = UserRepository()
    val groupRepository = GroupRepository()
    val messageRepository = MessageRepository()
    val dmRepository = DMRepository()
    val callRepository = CallRepository()

    /* ---------------------------------------------------
     * WebSocket manager
     * --------------------------------------------------- */
    val wsManager = WebSocketConnectionManager()

    /* ---------------------------------------------------
     * Services
     * --------------------------------------------------- */
    val userService = UserService(userRepository, wsManager)
    val groupService = GroupService(groupRepository, userRepository)
    val messageService = MessageService(messageRepository, userRepository, wsManager)
    val dmService = DMService(dmRepository, userRepository)
    val callService = CallService(
        callRepository,
        userRepository,
        groupService,
        dmService,
        userService,
        wsManager
    )

    /* ---------------------------------------------------
     * Plugins
     * --------------------------------------------------- */
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
    }

    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    /* ---------------------------------------------------
     * Authentication (NOW services exist)
     * --------------------------------------------------- */
    install(Authentication) {
        jwt {
            verifier(
                JWT.require(Algorithm.HMAC256("your-secret-key-change-in-production"))
                    .withIssuer("chat-app")
                    .build()
            )

            validate { credential ->
                val userId = credential.payload
                    .getClaim("userId")
                    .asString()
                    ?: return@validate null

                val user = userService.findById(userId)
                    ?: return@validate null

                UserPrincipal(user)
            }

            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "Invalid or expired token")
                )
            }
        }
    }

    /* ---------------------------------------------------
     * Routing
     * --------------------------------------------------- */
    routing {
        get("/") {
            call.respondText("Chat API Server is running", ContentType.Text.Plain)
        }

        authRoutes(userService)
        userRoutes(userService)
        groupRoutes(groupService, messageService)  // Add messageService here
        dmRoutes(dmService, messageService)
        callRoutes(callService)
        websocketRoute(wsManager, userService, callService)
    }

    if (userRepository.findAll().isEmpty()) {
        val testUser1 = User(
            id = "user1",
            username = "alice",
            displayName = "Alice",
            passwordHash = hashPassword("password"),
            createdAt = now()
        )

        val testUser2 = User(
            id = "user2",
            username = "bob",
            displayName = "Bob",
            passwordHash = hashPassword("password"),
            createdAt = now()
        )

        userRepository.save(testUser1)
        userRepository.save(testUser2)

        // Create a test conversation
        dmService.createOrGetConversation("user1", "user2")

        println("Created test users: alice/password and bob/password")
    }
}
