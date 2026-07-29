import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import network.UdpClient
import protocol.*
import java.net.Socket

fun main() = runBlocking {
    println("Buscando servidores...")
    val servers = UdpClient().scanServers()

    val server = if (servers.isNotEmpty()) {
        servers.forEachIndexed { i, s -> println("[$i] ${s.name} -> TCP ${s.tcp_port} (${s.state}, ${s.players} jugadores)") }
        print("Elige un índice (o Enter para el [0]): ")
        val idx = readLine()?.trim()?.toIntOrNull() ?: 0
        servers[idx]
    } else {
        println("No se encontró nada por broadcast. Conexión manual.")
        print("IP del servidor (Enter = localhost): ")
        val ip = readLine()?.trim()?.ifBlank { "localhost" } ?: "localhost"
        print("Puerto TCP: ")
        val port = readLine()?.trim()?.toIntOrNull() ?: 8889
        ServerInfoMsg(name = "manual", tcp_port = port, state = "lobby", players = 0).also {
            // reutilizamos el mismo flujo de abajo con esta IP
        }.let { it to ip }.let { (info, _) -> info } // placeholder, ver nota abajo
    }

    val hostIp = "localhost" // cámbialo si pruebas entre máquinas distintas
    val socket = Socket(hostIp, server.tcp_port)
    val output = socket.outputStream
    val input = socket.inputStream

    fun send(msg: CtfMessage) {
        val json = CtfJson.format.encodeToString(msg)
        output.write((json + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    launch(Dispatchers.IO) {
        val buffer = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) { println("[Servidor cerró la conexión]"); break }
            if (b == '\n'.code) {
                val line = buffer.toString(Charsets.UTF_8.name()).trimEnd('\r')
                buffer.reset()
                runCatching {
                    println(">> ${CtfJson.format.decodeFromString<CtfMessage>(line)}")
                }.onFailure { println("[No se pudo parsear: $line]") }
            } else buffer.write(b)
        }
    }

    print("Tu nombre: ")
    send(JoinMsg(name = readLine()?.trim()?.ifBlank { "Jugador" } ?: "Jugador"))

    println("Comandos: w/a/s/d mover, x quieto, e interactuar, raw <json> para enviar texto crudo, q salir")
    while (true) {
        val cmd = readLine()?.trim() ?: continue
        when {
            cmd == "w" -> send(InputMsg(Direction(0, -1)))
            cmd == "s" -> send(InputMsg(Direction(0, 1)))
            cmd == "a" -> send(InputMsg(Direction(-1, 0)))
            cmd == "d" -> send(InputMsg(Direction(1, 0)))
            cmd == "x" -> send(InputMsg(Direction(0, 0)))
            cmd == "e" -> send(InteractMsg())
            cmd == "q" -> { socket.close(); return@runBlocking }
            cmd.startsWith("raw ") -> {
                val raw = cmd.removePrefix("raw ")
                output.write((raw + "\n").toByteArray(Charsets.UTF_8)); output.flush()
            }
        }
    }
}