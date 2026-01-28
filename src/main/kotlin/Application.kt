import auth.UserPrincipal
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import repository.*
import routes.*
import service.*
import websocket.WebSocketConnectionManager
import java.time.Duration


fun main() {
    embeddedServer(Netty, port = 8080, host = "192.168.100.110") {
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

    initTestData(userRepository, dmRepository)


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

        // Allow WebSocket headers
        allowHeader("Sec-WebSocket-Key")
        allowHeader("Sec-WebSocket-Version")
        allowHeader("Sec-WebSocket-Extensions")
        allowHeader("Sec-WebSocket-Protocol")

        // Allow credentials for WebSocket
        allowCredentials = true
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
        // Serve static files (HTML, CSS, JS)
        static("/") {
            resources(".")  // Serve files from resources root
            defaultResource("client.html")  // Default to client.html
        }

        // API Routes
        authRoutes(userService)
        userRoutes(userService)
        groupRoutes(groupService, messageService)
        dmRoutes(dmService, messageService)
        callRoutes(callService)
        websocketRoute(wsManager, userService, callService)

        // Fallback route - redirect to client.html
        get("/") {
            call.respondRedirect("/client.html")
        }

        // Health check endpoint
        get("/health") {
            call.respondText("OK", ContentType.Text.Plain)
        }
    }

}

private suspend fun initTestData(userRepository: UserRepository, dmRepository: DMRepository) {
    // Create test users if none exist
    if (userRepository.findAll().isEmpty()) {
        println("Creating test users...")

        val user1 = models.User(
            id = "user1",
            username = "alice",
            displayName = "Alice",
            passwordHash = auth.hashPassword("password"),
            createdAt = kotlinx.datetime.Clock.System.now()
        )

        val user2 = models.User(
            id = "user2",
            username = "bob",
            displayName = "Bob",
            passwordHash = auth.hashPassword("password"),
            createdAt = kotlinx.datetime.Clock.System.now()
        )

        userRepository.save(user1)
        userRepository.save(user2)

        // Create a test DM conversation
        val dm = models.DirectMessageConversation(
            id = "dm1",
            participant1Id = "user1",
            participant2Id = "user2",
            createdAt = kotlinx.datetime.Clock.System.now()
        )
        dmRepository.save(dm)

        println("Test users created:")
        println("  alice / password")
        println("  bob / password")
    }
}
