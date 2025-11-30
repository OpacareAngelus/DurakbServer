package game

import data.Card
import data.GameState
import data.Move
import data.Player
import data.TableEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GameEngine {
    private val suits = listOf("♥", "♦", "♣", "♠")
    private val ranks = listOf("6", "7", "8", "9", "10", "Jack", "Queen", "King", "Ace")

    private var deck: MutableList<Card> = mutableListOf()
    var trumpSuit: String = ""
    private val players = mutableMapOf<String, Player>()
    var currentAttacker: String = ""
    private var table: MutableList<Pair<Card, Card?>> = mutableListOf()
    private val mutex = Mutex()

    private val stateChannel = Channel<GameState>(BUFFERED)
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        resetDeck()
    }

    private fun resetDeck() {
        deck.clear()
        for (suit in suits) {
            for (rank in ranks) {
                deck.add(Card(suit, rank))
            }
        }
        deck.shuffle()
        trumpSuit = deck.last().suit
    }

    suspend fun addPlayer(playerId: String, name: String): Boolean {
        return mutex.withLock {
            if (players.size >= 2) return false
            players[playerId] = Player(playerId, name, emptyList(), false)
            broadcastCurrentState("Игрок $name подключился (${players.size}/2)")
            true
        }
    }

    suspend fun removePlayer(playerId: String) {
        mutex.withLock {
            players.remove(playerId)
            if (players.isNotEmpty()) {
                broadcastCurrentState("Игрок $playerId покинул игру")
            }
        }
    }

    suspend fun startGame() {
        mutex.withLock {
            resetDeck()
            table.clear()
            players.keys.forEach { id ->
                val player = players[id] ?: return@forEach
                val handCards = deck.take(6).toMutableList()
                deck = deck.drop(6).toMutableList()
                val sortedHand = handCards.sortedWith(compareBy({ suits.indexOf(it.suit) }, { it.rankValue() }))
                players[id] = player.copy(hand = sortedHand)
            }
            currentAttacker = players.keys.firstOrNull() ?: return
            val attacker = players[currentAttacker] ?: return
            players[currentAttacker] = attacker.copy(isAttacker = true)
            broadcastCurrentState("Игра началась! Ход атакующего: разыграйте любую карту")
        }
    }

    suspend fun processMove(move: Move) {
        mutex.withLock {
            if (move.playerId != currentAttacker && move.playerId != getDefenderId()) return

            when (move.action) {
                "play_card" -> handlePlayCard(move)
                "pass" -> handlePass(move)
            }
        }
    }

    private suspend fun handlePlayCard(move: Move) {
        if (move.card == null) return
        val player = players[move.playerId] ?: return
        if (!player.hand.contains(move.card)) return

        if (move.playerId == currentAttacker) {
            if (table.isNotEmpty() && !table.any { it.first.rank == move.card.rank || it.second?.rank == move.card.rank }) return
            table.add(move.card to null)
        } else {
            val attackIndex = table.indexOfLast { it.second == null }
            if (attackIndex == -1) return
            val attackCard = table[attackIndex].first
            if (!move.card.beats(attackCard, trumpSuit)) return
            table[attackIndex] = attackCard to move.card
        }
        val updatedHand = (player.hand - move.card).sortedWith(compareBy({ suits.indexOf(it.suit) }, { it.rankValue() }))
        players[move.playerId] = player.copy(hand = updatedHand)
        broadcastCurrentState("Ход: ${move.card}")
        checkWinner()
    }

    private suspend fun handlePass(move: Move) {
        if (table.isEmpty()) return
        val defenderId = getDefenderId()
        if (move.playerId == currentAttacker) {
            if (table.any { it.second == null }) return
            broadcastCurrentState("Отбился!")
        } else {
            val takenCards = table.flatMap { listOfNotNull(it.first, it.second) }
            val defender = players[defenderId] ?: return
            val updatedHand = (defender.hand + takenCards).sortedWith(compareBy({ suits.indexOf(it.suit) }, { it.rankValue() }))
            players[defenderId] = defender.copy(hand = updatedHand)
            broadcastCurrentState("Игрок $defenderId взял карты.")
        }
        table.clear()
        refillHands()
        if (move.playerId == currentAttacker) {
            switchRoles()
        }
        broadcastCurrentState("Новый раунд.")
    }

    private suspend fun refillHands() {
        players.keys.forEach { id ->
            val player = players[id] ?: return@forEach
            val needed = 6 - player.hand.size
            if (needed > 0 && deck.isNotEmpty()) {
                val newCards = deck.take(needed).toMutableList()
                deck = deck.drop(needed).toMutableList()
                val updatedHand = (player.hand + newCards).sortedWith(compareBy({ suits.indexOf(it.suit) }, { it.rankValue() }))
                players[id] = player.copy(hand = updatedHand)
            }
        }
        broadcastCurrentState()
        checkWinner()
    }

    private fun switchRoles() {
        val defenderId = getDefenderId()
        currentAttacker = defenderId
        players.forEach { (id, player) ->
            players[id] = player.copy(isAttacker = id == currentAttacker)
        }
    }

    private fun getDefenderId(): String {
        return players.keys.firstOrNull { it != currentAttacker } ?: ""
    }

    private suspend fun checkWinner() {
        val winner = players.entries.firstOrNull { it.value.hand.isEmpty() }?.key
        if (winner != null) {
            broadcastCurrentState("Игра окончена. Победитель: $winner", winner)
            scope.launch {
                delay(2000)
                startGame()
            }
        }
    }

    private suspend fun broadcastCurrentState(message: String? = null, winner: String? = null) {
        val state = if (players.size < 2) {
            GameState(
                players = emptyList(),
                table = emptyList(),
                deckSize = 0,
                trumpSuit = "",
                currentAttacker = null,
                winner = null,
                message = message ?: "Ожидание соперника (${players.size}/2)"
            )
        } else {
            GameState(
                players = players.values.toList(),
                table = table.map { TableEntry(it.first, it.second) },
                deckSize = deck.size,
                trumpSuit = trumpSuit,
                currentAttacker = currentAttacker,
                winner = winner,
                message = message
            )
        }
        stateChannel.trySend(state)
    }

    fun getStateChannel(): Channel<GameState> = stateChannel

    private fun Card.rankValue(): Int {
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