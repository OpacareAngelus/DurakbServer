package data

import kotlinx.serialization.Serializable

@Serializable
data class GameState(
    val players: List<Player>,
    val table: List<TableEntry>,
    val deckSize: Int,
    val trumpSuit: String,
    val currentAttacker: String?,
    val winner: String?,
    val message: String?
)