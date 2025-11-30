package server

import com.corundumstudio.socketio.Configuration
import com.corundumstudio.socketio.SocketIOClient
import com.corundumstudio.socketio.SocketIOServer
import data.ClientMessage
import data.ServerMessage
import game.GameEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.*
import java.util.concurrent.ConcurrentHashMap

val rooms = ConcurrentHashMap<String, Room>()
val json = Json { ignoreUnknownKeys = true; isLenient = true }
val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

fun main() {
    val config = Configuration().apply {
        hostname = "0.0.0.0"
        port = 13177
        origin = "*"
    }

    val server = SocketIOServer(config)
    val namespace = server.addNamespace("/cardGame")

    namespace.addConnectListener { client ->
        println("Client connected: ${client.sessionId}")
    }

    namespace.addDisconnectListener { client ->
        println("Client disconnected: ${client.sessionId}")
        runBlocking {
            handleDisconnect(client)
        }
    }

    namespace.addEventListener("message", String::class.java) { client, data, _ ->
        try {
            val msg = json.decodeFromString<ClientMessage>(data)
            runBlocking {
                handleMessage(client, msg)
            }
        } catch (e: Exception) {
            client.sendEvent("message", json.encodeToString(ServerMessage.serializer(), ServerMessage(type = "error", error = "Invalid JSON: ${e.message}")))
        }
    }

    server.start()
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
}

data class Room(
    val code: String,
    val engine: GameEngine,
    val connections: MutableMap<String, SocketIOClient> = ConcurrentHashMap()
) {
    init {
        scope.launch {
            println("Room $code: Starting state broadcast listener")
            try {
                engine.getStateChannel().consumeEach { state ->
                    val msgJson = json.encodeToString(
                        ServerMessage.serializer(),
                        ServerMessage(type = "state", gameState = state)
                    )
                    println("Room $code: Broadcasting state to ${connections.size} players: $msgJson")
                    connections.values.forEach { conn ->
                        scope.launch {
                            try {
                                conn.sendEvent("message", msgJson)
                            } catch (e: Exception) {
                                println("Room $code: Failed to send state to conn: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("Room $code: Broadcast listener error: ${e.message}")
            }
        }
    }

    suspend fun addPlayer(playerId: String, client: SocketIOClient, name: String): Boolean {
        println("Room $code: Attempting to add player $playerId ($name)")
        if (engine.addPlayer(playerId, name)) {
            connections[playerId] = client
            println("Room $code: Added player $playerId, total: ${connections.size}")
            if (connections.size == 2) {
                println("Room $code: Starting game!")
                engine.startGame()
            }
            return true
        } else {
            println("Room $code: Failed to add player $playerId to engine")
            return false
        }
    }

    suspend fun removePlayer(playerId: String) {
        println("Room $code: Removing player $playerId")
        engine.removePlayer(playerId)
        connections.remove(playerId)
        if (connections.isEmpty()) {
            println("Room $code: Empty, removing room")
            rooms.remove(code)
        }
    }
}

suspend fun handleMessage(
    client: SocketIOClient,
    msg: ClientMessage
) {
    val mutex = Mutex()
    mutex.withLock {
        when (msg.type) {
            "create" -> {
                var code: String
                do {
                    code = (1000..9999).random().toString()
                } while (rooms.containsKey(code))
                println("Creating room with code: $code")
                val engine = GameEngine()
                val room = Room(code, engine)
                rooms[code] = room
                val playerId = UUID.randomUUID().toString().substring(0, 8)
                client.joinRoom(code)
                val name = msg.name ?: "Хост"
                if (!room.addPlayer(playerId, client, name)) {
                    client.sendEvent(
                        "message",
                        json.encodeToString(ServerMessage.serializer(), ServerMessage(type = "error", error = "Failed to add host"))
                    )
                    return
                }
                client.sendEvent(
                    "message",
                    json.encodeToString(ServerMessage.serializer(), ServerMessage(type = "room_created", code = code, playerId = playerId))
                )
                println("Room $code: Sent room_created to host $playerId")
            }

            "join" -> {
                val code = msg.code ?: run {
                    println("Join: No code provided")
                    client.sendEvent(
                        "message",
                        json.encodeToString(ServerMessage.serializer(), ServerMessage(type = "error", error = "No code"))
                    )
                    return
                }
                println("Join attempt for code: $code")
                val room = rooms[code] ?: run {
                    println("Join: Room $code not found")
                    client.sendEvent(
                        "message",
                        json.encodeToString(ServerMessage.serializer(), ServerMessage(type = "error", error = "Room not found"))
                    )
                    return
                }
                if (room.connections.size >= 2) {
                    println("Join: Room $code full")
                    client.sendEvent(
                        "message",
                        json.encodeToString(ServerMessage.serializer(), ServerMessage(type = "error", error = "Room full"))
                    )
                    return
                }
                val playerId = UUID.randomUUID().toString().substring(0, 8)
                client.joinRoom(code)
                val name = msg.name ?: "Player"
                if (!room.addPlayer(playerId, client, name)) {
                    client.sendEvent(
                        "message",
                        json.encodeToString(ServerMessage.serializer(), ServerMessage(type = "error", error = "Failed to join"))
                    )
                    return
                }
                client.sendEvent(
                    "message",
                    json.encodeToString(ServerMessage.serializer(), ServerMessage(type = "joined", playerId = playerId, code = code))
                )
                println("Room ${room.code}: Sent joined to player $playerId")
            }

            "leave" -> {
                val code = client.allRooms.firstOrNull { key: String -> rooms.containsKey(key) } ?: return
                val room = rooms[code] ?: return
                val playerId = getPlayerIdByClient(client, room) ?: return
                room.removePlayer(playerId)
                client.leaveRoom(code)
                println("Room $code: Player $playerId left via message")
            }

            "move" -> {
                val code = client.allRooms.firstOrNull { key: String -> rooms.containsKey(key) } ?: return
                val room = rooms[code] ?: return
                val playerId = getPlayerIdByClient(client, room) ?: return
                val move = msg.move ?: run {
                    println("Invalid move: no move data")
                    return
                }
                room.engine.processMove(move.copy(playerId = playerId))
                println("Room ${room.code}: Processed move from $playerId")
            }

            else -> {
                println("Invalid message type: ${msg.type}")
            }
        }
    }
}

suspend fun handleDisconnect(
    client: SocketIOClient
) {
    val code = client.allRooms.firstOrNull { key: String -> rooms.containsKey(key) } ?: return
    val room = rooms[code] ?: return
    val playerId = getPlayerIdByClient(client, room) ?: return

    room.removePlayer(playerId)
    client.leaveRoom(code)
}

fun getPlayerIdByClient(client: SocketIOClient, room: Room): String? {
    return room.connections.entries.firstOrNull { entry -> entry.value == client }?.key
}