package data

import kotlinx.serialization.Serializable

@Serializable
data class ServerMessage(
    val type: String,
    val code: String? = null,
    val playerId: String? = null,
    val error: String? = null,
    val gameState: GameState? = null
)