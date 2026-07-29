package engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import protocol.*
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

enum class GamePhase {
    LOBBY, COUNTDOWN, PLAYING, POST_GAME
}

data class ServerPlayer(
    val id: String,
    val name: String,
    var x: Double = 0.0,
    var y: Double = 0.0,
    var dx: Int = 0,
    var dy: Int = 0,
    var isConnected: Boolean = true
)

private sealed class InboxEvent {
    data class PlayerMessage(val connectionId: String, val message: CtfMessage) : InboxEvent()
    data class Disconnected(val connectionId: String) : InboxEvent()
    object Tick : InboxEvent()
    object CountdownTick : InboxEvent()
    object ResetLobby : InboxEvent()
}

class GameEngine(
    private val sendToClient: (String, CtfMessage) -> Unit,
    private val broadcast: (CtfMessage) -> Unit,
    private val closeConnection: (String, ErrorCode) -> Unit
) {
    @Volatile var phase = GamePhase.LOBBY
        private set

    private val players = ConcurrentHashMap<String, ServerPlayer>()

    @Volatile private var flagOwner: String? = null
    @Volatile private var flagX: Double = GameConfig.MAP_SIZE / 2.0
    @Volatile private var flagY: Double = GameConfig.MAP_SIZE / 2.0

    // FIX 1: rastrea si el portador actual ya estuvo dentro del círculo desde que tomó la bandera.
    // Sin esto, alguien que roba estando ya fuera gana instantáneamente (viola sección 3.3 y 6.4).
    @Volatile private var flagCarrierInsideCircle: Boolean = false

    private val inbox = Channel<InboxEvent>(capacity = Channel.UNLIMITED)
    private var engineScope: CoroutineScope? = null

    @Volatile private var countdownValue = 5
    private var countdownJob: Job? = null
    private var gameLoopJob: Job? = null

    fun startProcessingLoop(scope: CoroutineScope) {
        engineScope = scope
        scope.launch {
            for (event in inbox) {
                when (event) {
                    is InboxEvent.PlayerMessage -> processMessageSafely(event.connectionId, event.message)
                    is InboxEvent.Disconnected -> handleDisconnect(event.connectionId)
                    is InboxEvent.Tick -> processPhysicsTick()
                    is InboxEvent.CountdownTick -> processCountdownTick()
                    is InboxEvent.ResetLobby -> resetToLobby()
                }
            }
        }
    }

    suspend fun receiveMessage(connectionId: String, message: CtfMessage) {
        inbox.send(InboxEvent.PlayerMessage(connectionId, message))
    }

    suspend fun notifyDisconnect(connectionId: String) {
        inbox.send(InboxEvent.Disconnected(connectionId))
    }

    // Necesario para que UdpServer reporte el conteo real de jugadores en server_info (pendiente de conectar)
    fun getPlayerCount(): Int = players.size

    private fun processMessageSafely(connectionId: String, message: CtfMessage) {
        when (message) {
            is JoinMsg -> handleJoin(connectionId, message)
            is InputMsg -> handleInput(connectionId, message)
            is InteractMsg -> handleInteract(connectionId)
            else -> sendToClient(connectionId, ErrorMsg(ErrorCode.UNKNOWN_TYPE.name))
        }
    }

    private fun handleJoin(connectionId: String, message: JoinMsg) {
        if (message.v != 1) {
            closeConnection(connectionId, ErrorCode.VERSION_MISMATCH)
            return
        }
        if (phase != GamePhase.LOBBY) {
            closeConnection(connectionId, ErrorCode.GAME_STARTED)
            return
        }
        if (players.containsKey(connectionId)) {
            sendToClient(connectionId, ErrorMsg(ErrorCode.INVALID_PHASE.name))
            return
        }

        val trimmedName = message.name.trim()
        if (trimmedName.isEmpty() || trimmedName.length > GameConfig.NAME_MAX_LENGTH || trimmedName.any { it.isISOControl() }) {
            sendToClient(connectionId, ErrorMsg(ErrorCode.NAME_INVALID.name))
            return
        }

        val newPlayer = ServerPlayer(id = connectionId, name = trimmedName)
        players[connectionId] = newPlayer

        val welcome = WelcomeMsg(
            player_id = connectionId,
            config = Config(
                map_size = GameConfig.MAP_SIZE,
                circle_radius = GameConfig.CIRCLE_RADIUS,
                player_radius = GameConfig.PLAYER_RADIUS,
                interact_radius = GameConfig.INTERACT_RADIUS,
                speed = GameConfig.SPEED,
                tick_rate = GameConfig.TICK_RATE
            )
        )
        sendToClient(connectionId, welcome)
        broadcastLobbyState()
        checkLobbyConditions()
    }

    private fun handleInput(connectionId: String, message: InputMsg) {
        if (phase != GamePhase.PLAYING) {
            sendToClient(connectionId, ErrorMsg(ErrorCode.INVALID_PHASE.name))
            return
        }
        val player = players[connectionId] ?: return

        if (message.dir.x !in -1..1 || message.dir.y !in -1..1) {
            sendToClient(connectionId, ErrorMsg(ErrorCode.INVALID_FIELD.name))
            return
        }
        player.dx = message.dir.x
        player.dy = message.dir.y
        println("DEBUG: input recibido de $connectionId -> dx=${player.dx}, dy=${player.dy}")
    }

    private fun handleInteract(connectionId: String) {
        if (phase != GamePhase.PLAYING) {
            sendToClient(connectionId, ErrorMsg(ErrorCode.INVALID_PHASE.name))
            return
        }
        val player = players[connectionId] ?: return

        // FIX 2: interact del propio portador es no-op (sección 5.3), no "suelta" la bandera.
        if (flagOwner == connectionId) {
            return
        }

        if (flagOwner == null) {
            // Intento de captura
            val distSq = (player.x - flagX) * (player.x - flagX) + (player.y - flagY) * (player.y - flagY)
            if (distSq <= GameConfig.INTERACT_RADIUS * GameConfig.INTERACT_RADIUS) {
                flagOwner = connectionId
                flagCarrierInsideCircle = isInsideCircle(player.x, player.y)
            }
            return
        }

        // Intento de robo
        val currentOwner = players[flagOwner]
        if (currentOwner != null) {
            val distSq = (player.x - currentOwner.x) * (player.x - currentOwner.x) +
                    (player.y - currentOwner.y) * (player.y - currentOwner.y)
            if (distSq <= GameConfig.INTERACT_RADIUS * GameConfig.INTERACT_RADIUS) {
                flagOwner = connectionId
                flagCarrierInsideCircle = isInsideCircle(player.x, player.y)
            }
        }
    }

    private fun isInsideCircle(x: Double, y: Double): Boolean {
        val centerX = GameConfig.MAP_SIZE / 2.0
        val centerY = GameConfig.MAP_SIZE / 2.0
        val victoryDistance = GameConfig.CIRCLE_RADIUS + GameConfig.PLAYER_RADIUS
        val distSq = (x - centerX) * (x - centerX) + (y - centerY) * (y - centerY)
        return distSq <= victoryDistance * victoryDistance
    }

    private fun checkLobbyConditions() {
        if (phase == GamePhase.LOBBY && players.size >= GameConfig.MIN_PLAYERS && countdownJob == null) {
            phase = GamePhase.COUNTDOWN
            countdownValue = 5

            countdownJob = engineScope?.launch {
                while (countdownValue > 0 && phase == GamePhase.COUNTDOWN) {
                    inbox.send(InboxEvent.CountdownTick)
                    delay(1000)
                }
            }
            println("Iniciando Countdown.")
        }
    }

    private fun processCountdownTick() {
        if (phase != GamePhase.COUNTDOWN) return
        broadcast(CountdownMsg(countdownValue))
        countdownValue--

        if (countdownValue == 0) {
            startGame()
        }
    }

    private fun startGame() {
        phase = GamePhase.PLAYING
        flagOwner = null
        flagX = GameConfig.MAP_SIZE / 2.0
        flagY = GameConfig.MAP_SIZE / 2.0
        flagCarrierInsideCircle = false

        val random = kotlin.random.Random
        players.values.forEach { p ->
            val angle = random.nextDouble(0.0, 2 * Math.PI)
            val r = random.nextDouble(350.0, 450.0)
            p.x = (GameConfig.MAP_SIZE / 2.0) + r * kotlin.math.cos(angle)
            p.y = (GameConfig.MAP_SIZE / 2.0) + r * kotlin.math.sin(angle)
            p.dx = 0
            p.dy = 0
        }

        broadcast(StartMsg())

        gameLoopJob = engineScope?.launch {
            val interval = 1000L / GameConfig.TICK_RATE
            while (phase == GamePhase.PLAYING) {
                val start = System.currentTimeMillis()
                inbox.send(InboxEvent.Tick)
                val elapsed = System.currentTimeMillis() - start
                val delayTime = interval - elapsed
                if (delayTime > 0) delay(delayTime)
            }
        }
    }

    private fun processPhysicsTick() {
        if (phase != GamePhase.PLAYING) return
        val dt = 1.0 / GameConfig.TICK_RATE

        players.values.forEach { player ->
            if (player.dx != 0 || player.dy != 0) {
                val magnitude = sqrt((player.dx * player.dx + player.dy * player.dy).toDouble())
                val vx = (player.dx / magnitude) * (GameConfig.SPEED * dt)
                val vy = (player.dy / magnitude) * (GameConfig.SPEED * dt)

                player.x += vx
                player.y += vy

                val minBound = GameConfig.PLAYER_RADIUS
                val maxBound = GameConfig.MAP_SIZE - GameConfig.PLAYER_RADIUS
                player.x = player.x.coerceIn(minBound, maxBound)
                player.y = player.y.coerceIn(minBound, maxBound)
            }
        }

        val currentOwnerId = flagOwner
        if (currentOwnerId != null) {
            val owner = players[currentOwnerId]
            if (owner != null) {
                flagX = owner.x
                flagY = owner.y

                // FIX 1: solo se gana en la transición real dentro -> fuera
                val inside = isInsideCircle(owner.x, owner.y)
                if (inside) {
                    flagCarrierInsideCircle = true
                } else if (flagCarrierInsideCircle) {
                    triggerGameOver(currentOwnerId)
                    return
                }
            }
        }

        broadcastGameState()
    }

    private fun triggerGameOver(winnerId: String) {
        phase = GamePhase.POST_GAME
        gameLoopJob?.cancel()
        broadcast(GameOverMsg(winnerId))
        println("¡Juego terminado! Ganador: $winnerId")

        engineScope?.launch {
            delay(GameConfig.POST_GAME_SECONDS * 1000L)
            inbox.send(InboxEvent.ResetLobby)
        }
    }

    private fun resetToLobby() {
        phase = GamePhase.LOBBY
        players.values.forEach {
            it.dx = 0
            it.dy = 0
        }
        flagOwner = null
        flagX = GameConfig.MAP_SIZE / 2.0
        flagY = GameConfig.MAP_SIZE / 2.0
        flagCarrierInsideCircle = false
        countdownJob = null
        gameLoopJob = null

        broadcastLobbyState()
        checkLobbyConditions()
    }

    private fun handleDisconnect(connectionId: String) {
        players.remove(connectionId)
        if (flagOwner == connectionId) {
            flagOwner = null
            flagX = GameConfig.MAP_SIZE / 2.0
            flagY = GameConfig.MAP_SIZE / 2.0
            flagCarrierInsideCircle = false
        }

        if (phase == GamePhase.LOBBY) {
            broadcastLobbyState()
        } else if (phase == GamePhase.COUNTDOWN && players.size < GameConfig.MIN_PLAYERS) {
            countdownJob?.cancel()
            countdownJob = null
            phase = GamePhase.LOBBY
            broadcastLobbyState()
            println("Countdown abortado: insuficientes jugadores.")
        } else if (players.isEmpty() && phase != GamePhase.LOBBY) {
            // FIX 3: si todos se desconectan durante PLAYING/POST_GAME, el servidor no debe quedar trabado
            gameLoopJob?.cancel()
            countdownJob?.cancel()
            countdownJob = null
            gameLoopJob = null
            resetToLobby()
        }
    }

    private fun broadcastLobbyState() {
        val lobbyPlayers = players.values.map { LobbyPlayer(it.id, it.name) }
        broadcast(LobbyMsg(lobbyPlayers))
    }

    private fun broadcastGameState() {
        val playerStates = players.values.map {
            PlayerState(it.id, roundHalfAwayFromZero(it.x), roundHalfAwayFromZero(it.y))
        }
        val stateMsg = StateMsg(
            flag = FlagState(flagOwner, roundHalfAwayFromZero(flagX), roundHalfAwayFromZero(flagY)),
            players = playerStates
        )
        broadcast(stateMsg)
    }

    private fun roundHalfAwayFromZero(value: Double): Double {
        return value.toBigDecimal().setScale(1, RoundingMode.HALF_UP).toDouble()
    }
}