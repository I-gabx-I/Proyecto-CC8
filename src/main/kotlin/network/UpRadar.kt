package network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import protocol.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

// --- SERVIDOR UDP (El que responde) ---

class UdpServer(
    private val serverName: String,
    private val tcpPort: Int,
    private val statusProvider: () -> Pair<String, Int> // (state, players)
) {
    @Volatile private var running = false
    private var socket: DatagramSocket? = null

    suspend fun start() = withContext(Dispatchers.IO) {
        running = true
        socket = DatagramSocket(GameConfig.DISCOVERY_PORT).apply {
            reuseAddress = true
        }
        
        val buffer = ByteArray(GameConfig.MESSAGE_MAX_SIZE)
        println("Radar UDP: Escuchando 'discover' en el puerto ${GameConfig.DISCOVERY_PORT}...")

        while (running) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket?.receive(packet)
                
                val jsonString = String(packet.data, 0, packet.length).trim()
                val message = CtfJson.format.decodeFromString<CtfMessage>(jsonString)
                
                if (message is DiscoverMsg && message.v == 1) {
                    println("Ping de descubrimiento recibido de ${packet.address.hostAddress}")
                    
                    // Ahora consulta el estado real del GameEngine en vez de valores fijos
                    val (state, playerCount) = statusProvider()
                    val response = ServerInfoMsg(
                        name = serverName,
                        tcp_port = tcpPort,
                        state = state,
                        players = playerCount
                    )
                    
                    val responseJson = CtfJson.format.encodeToString<CtfMessage>(response)
                    val responseBytes = responseJson.toByteArray()
                    
                    val responsePacket = DatagramPacket(
                        responseBytes,
                        responseBytes.size,
                        packet.address,
                        packet.port
                    )
                    socket?.send(responsePacket)
                }
            } catch (e: Exception) {
                if (running) println("Error en Radar UDP o JSON ignorado: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        socket?.close()
    }
}

// --- CLIENTE UDP (El que busca) ---

class UdpClient {
    
    // CORRECCIÓN: Cálculo de subred para el broadcast dual
    private fun getSubnetBroadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        NetworkInterface.getNetworkInterfaces().asSequence().forEach { iface ->
            if (iface.isUp && !iface.isLoopback) {
                iface.interfaceAddresses.forEach { addr ->
                    addr.broadcast?.let { addresses.add(it) }
                }
            }
        }
        return addresses
    }

    suspend fun scanServers(): List<ServerInfoMsg> = withContext(Dispatchers.IO) {
        val serversFound = mutableListOf<ServerInfoMsg>()
        val socket = DatagramSocket().apply {
            broadcast = true
            soTimeout = 2000
        }

        try {
            val discoverJson = CtfJson.format.encodeToString<CtfMessage>(DiscoverMsg())
            val bytes = discoverJson.toByteArray()
            
            // CORRECCIÓN: Lista de objetivos (255.255.255.255 + subredes locales)
            val targets = listOf(InetAddress.getByName("255.255.255.255")) + getSubnetBroadcastAddresses()
            
            targets.forEach { target ->
                try {
                    val packet = DatagramPacket(bytes, bytes.size, target, GameConfig.DISCOVERY_PORT)
                    socket.send(packet)
                } catch (e: Exception) {
                    // Ignorar silenciosamente si una interfaz particular falla al enviar
                }
            }
            println("Buscando servidores en la red (Broadcast Dual)...")

            val buffer = ByteArray(GameConfig.MESSAGE_MAX_SIZE)
            while (true) {
                val responsePacket = DatagramPacket(buffer, buffer.size)
                socket.receive(responsePacket)
                
                val responseJson = String(responsePacket.data, 0, responsePacket.length).trim()
                val message = CtfJson.format.decodeFromString<CtfMessage>(responseJson)
                
                if (message is ServerInfoMsg) {
                    // Evitamos duplicados por si el server nos responde por ambas vías
                    if (serversFound.none { it.tcp_port == message.tcp_port && it.name == message.name }) {
                        serversFound.add(message)
                        println("Servidor CTF encontrado: '${message.name}' -> Jugar en TCP ${message.tcp_port}")
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            println("Escaneo UDP finalizado. Encontrados: ${serversFound.size}")
        } catch (e: Exception) {
            println("Error al buscar servidores: ${e.message}")
        } finally {
            socket.close()
        }
        
        return@withContext serversFound
    }
}