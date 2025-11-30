package data

import kotlinx.serialization.Serializable

@Serializable
data class Move(
    val playerId: String,
    val action: String,
    val card: Card? = null
)