package client

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import protocol.*

// Guardamos el estado global del cliente aquí para que Compose lo lea fácilmente
class GameState {
    // Fase actual (LOBBY, COUNTDOWN, PLAYING, etc.)
    var currentPhase by mutableStateOf("LOBBY")

    // Información del mapa y físicas (la recibimos en el WelcomeMsg)
    var config by mutableStateOf<Config?>(null)

    // Mi propio ID para saber quién soy yo en la lista de jugadores
    var myPlayerId by mutableStateOf("")

    // Jugadores en el Lobby
    var lobbyPlayers by mutableStateOf<List<LobbyPlayer>>(emptyList())

    // El estado del juego en tiempo real (posiciones)
    var currentGameState by mutableStateOf<StateMsg?>(null)

    // Tiempo restante antes de empezar
    var countdownSeconds by mutableStateOf(0)

    // Mensajes de error o fin de juego
    var systemMessage by mutableStateOf("")

    // ID del ganador (se setea en GAME_OVER)
    var lastWinnerId by mutableStateOf("")
}