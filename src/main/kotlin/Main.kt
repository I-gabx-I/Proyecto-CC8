import engine.GamePhase
import kotlinx.coroutines.*
import network.TcpServer
import network.UdpServer

fun main() = runBlocking {
    val tcpPort = 8889
    val serverName = "Servidor CC8"

    val tcpServer = TcpServer(tcpPort)

    // El UDP consulta el estado real del mismo GameEngine que usa el TCP,
    // no una instancia separada — así server_info nunca miente.
    val udpServer = UdpServer(
        serverName = serverName,
        tcpPort = tcpPort,
        statusProvider = {
            val phase = tcpServer.gameEngine.phase
            val state = if (phase == GamePhase.LOBBY) "lobby" else "playing"
            state to tcpServer.gameEngine.getPlayerCount()
        }
    )

    // Cierre ordenado si detienes el proceso (Ctrl+C)
    Runtime.getRuntime().addShutdownHook(Thread {
        println("Cerrando servidor...")
        tcpServer.stop()
        udpServer.stop()
    })

    println("=== Servidor CTF iniciado ===")
    println("TCP puerto: $tcpPort | UDP descubrimiento: 8888")

    val tcpJob = launch { tcpServer.start() }
    val udpJob = launch { udpServer.start() }

    joinAll(tcpJob, udpJob)
}