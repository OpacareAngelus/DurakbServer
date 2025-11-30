package data

import kotlinx.serialization.Serializable

@Serializable
data class ClientMessage(
    val type: String,
    val code: String? = null,
    val move: Move? = null,
    val name: String? = null
)