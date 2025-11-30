package data

import kotlinx.serialization.Serializable

@Serializable
data class TableEntry(
    val attack: Card,
    val defend: Card?
)