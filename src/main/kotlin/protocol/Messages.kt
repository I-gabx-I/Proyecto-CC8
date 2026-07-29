package protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

// 1. Motor de configuración JSON con el parche maestro
object CtfJson {
    val format = Json {
        encodeDefaults = true 
        ignoreUnknownKeys = true // Cumple la regla de "Lectura tolerante"[cite: 2]
        classDiscriminator = "type" // Hace que el polimorfismo funcione
    }
}

// 2. Clase base para agrupar todos los mensajes
@Serializable
sealed class CtfMessage

// --- MENSAJES DE DESCUBRIMIENTO (UDP) ---

@Serializable
@SerialName("discover")
data class DiscoverMsg(val v: Int = 1) : CtfMessage() //[cite: 2]

@Serializable
@SerialName("server_info")
data class ServerInfoMsg(
    val v: Int = 1,
    val name: String,
    val tcp_port: Int,
    val state: String,
    val players: Int
) : CtfMessage() //[cite: 2]

// --- MENSAJES DE CLIENTE A SERVIDOR (TCP) ---

@Serializable
@SerialName("join")
data class JoinMsg(
    val v: Int = 1,
    val name: String
) : CtfMessage() //[cite: 2]

@Serializable
@SerialName("input")
data class InputMsg(val dir: Direction) : CtfMessage() //[cite: 2]

@Serializable
data class Direction(val x: Int, val y: Int) //[cite: 2]

@Serializable
@SerialName("interact")
class InteractMsg : CtfMessage() //[cite: 2]

// --- MENSAJES DE SERVIDOR A CLIENTE (TCP) ---

@Serializable
@SerialName("welcome")
data class WelcomeMsg(
    val player_id: String,
    val config: Config
) : CtfMessage() //[cite: 2]

@Serializable
data class Config(
    val map_size: Double, val circle_radius: Double,
    val player_radius: Double, val interact_radius: Double,
    val speed: Double, val tick_rate: Int
) //[cite: 2]

@Serializable
@SerialName("lobby")
data class LobbyMsg(val players: List<LobbyPlayer>) : CtfMessage() //[cite: 2]

@Serializable
data class LobbyPlayer(val id: String, val name: String) //[cite: 2]

@Serializable
@SerialName("countdown")
data class CountdownMsg(val seconds: Int) : CtfMessage() //[cite: 2]

@Serializable
@SerialName("start")
class StartMsg : CtfMessage() //[cite: 2]

@Serializable
@SerialName("state")
data class StateMsg(
    val flag: FlagState,
    val players: List<PlayerState>
) : CtfMessage() //[cite: 2]

@Serializable
data class FlagState(
    val owner: String?, // null significa bandera libre[cite: 2]
    val x: Double,
    val y: Double
) //[cite: 2]

@Serializable
data class PlayerState(
    val id: String,
    val x: Double,
    val y: Double
) //[cite: 2]

@Serializable
@SerialName("game_over")
data class GameOverMsg(val winner: String) : CtfMessage() //[cite: 2]

@Serializable
@SerialName("error")
data class ErrorMsg(val reason: String) : CtfMessage() //[cite: 2]