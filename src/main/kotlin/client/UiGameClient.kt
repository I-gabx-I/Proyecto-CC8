package client

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import protocol.*
import java.net.Socket

class UiGameClient(private val state: GameState) {
    private var socket: Socket? = null
    private var output: java.io.OutputStream? = null
    private var input: java.io.InputStream? = null
    private var clientScope: CoroutineScope? = null
    private val writeMutex = Mutex()

    // Conectar al servidor de forma asíncrona
    fun connect(ip: String, port: Int, playerName: String, scope: CoroutineScope) {
        clientScope = scope
        scope.launch(Dispatchers.IO) {
            try {
                socket = Socket(ip, port)
                output = socket?.outputStream
                input = socket?.inputStream

                startListening()

                // Apenas conectamos, enviamos nuestro JoinMsg
                send(JoinMsg(name = playerName))
            } catch (e: Exception) {
                state.systemMessage = "Error al conectar: ${e.message}"
            }
        }
    }

    private fun startListening() {
        clientScope?.launch(Dispatchers.IO) {
            val buffer = java.io.ByteArrayOutputStream()
            val inputStream = input ?: return@launch

            while (true) {
                try {
                    val b = inputStream.read()
                    if (b == -1) {
                        state.systemMessage = "Desconectado del servidor"
                        break
                    }
                    if (b == '\n'.code) {
                        val line = buffer.toString(Charsets.UTF_8.name()).trimEnd('\r')
                        buffer.reset()
                        processMessage(line)
                    } else {
                        buffer.write(b)
                    }
                } catch (e: Exception) {
                    state.systemMessage = "Error de lectura: ${e.message}"
                    break
                }
            }
        }
    }

    private fun processMessage(jsonLine: String) {
        try {
            val msg = CtfJson.format.decodeFromString<CtfMessage>(jsonLine)
            // Actualizamos nuestro GameState en base a lo que manda el servidor
            when (msg) {
                is WelcomeMsg -> {
                    state.myPlayerId = msg.player_id
                    state.config = msg.config
                }
                is LobbyMsg -> {
                    state.currentPhase = "LOBBY"
                    state.lobbyPlayers = msg.players
                }
                is CountdownMsg -> {
                    state.currentPhase = "COUNTDOWN"
                    state.countdownSeconds = msg.seconds
                }
                is StartMsg -> {
                    state.currentPhase = "PLAYING"
                }
                is StateMsg -> {
                    state.currentGameState = msg
                }
                is GameOverMsg -> {
                    state.currentPhase = "GAME_OVER"
                    state.lastWinnerId = msg.winner
                    state.systemMessage = "¡Juego terminado! Ganador: ${msg.winner}"
                }
                is ErrorMsg -> {
                    state.systemMessage = "Error del servidor: ${msg.reason}"
                }
                else -> {}
            }
        } catch (e: Exception) {
            println("No se pudo parsear: $jsonLine")
        }
    }

    fun send(msg: CtfMessage) {
        clientScope?.launch(Dispatchers.IO) {
            writeMutex.withLock {
                try {
                    val json = CtfJson.format.encodeToString<CtfMessage>(msg)
                    output?.write((json + "\n").toByteArray(Charsets.UTF_8))
                    output?.flush()
                } catch (e: Exception) {
                    println("Error enviando: ${e.message}")
                }
            }
        }
    }

    fun disconnect() {
        socket?.close()
    }
}