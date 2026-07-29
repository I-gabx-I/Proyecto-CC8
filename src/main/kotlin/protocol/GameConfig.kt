package protocol
object GameConfig {
    // Constantes del mapa y jugadores
    const val MAP_SIZE = 1000.0
    const val CIRCLE_RADIUS = 300.0
    const val PLAYER_RADIUS = 15.0
    const val INTERACT_RADIUS = 40.0
    const val SPEED = 200.0
    const val TICK_RATE = 20

    // Constantes del servidor
    const val COUNTDOWN_SECONDS = 5
    const val MIN_PLAYERS = 2
    const val POST_GAME_SECONDS = 5
    const val DISCOVERY_PORT = 8888
    
    // Límites de red[cite: 2]
    const val MAX_PLAYERS = 100
    const val NAME_MAX_LENGTH = 20
    const val MESSAGE_MAX_SIZE = 65536 // 64 KB en bytes
}