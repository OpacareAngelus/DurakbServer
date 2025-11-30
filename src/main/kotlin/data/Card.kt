package data

import kotlinx.serialization.Serializable

@Serializable
data class Card(
    val suit: String,
    val rank: String
) {
    override fun toString(): String {
        return "$rank of $suit"
    }

    fun beats(other: Card, trumpSuit: String): Boolean {
        if (suit == other.suit) {
            return rankValue() > other.rankValue()
        }
        return suit == trumpSuit
    }

    private fun rankValue(): Int {
        return when (rank) {
            "6" -> 6
            "7" -> 7
            "8" -> 8
            "9" -> 9
            "10" -> 10
            "Jack" -> 11
            "Queen" -> 12
            "King" -> 13
            "Ace" -> 14
            else -> 0
        }
    }
}