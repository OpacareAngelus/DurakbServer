package data

import kotlinx.serialization.Serializable

@Serializable
data class Player(
    val id: String,
    val name: String,
    val hand: List<Card>,
    val isAttacker: Boolean
)