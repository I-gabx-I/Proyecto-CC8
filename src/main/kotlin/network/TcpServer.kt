package network

import engine.GameEngine
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import protocol.*
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MessageTooLargeException : Exception("Mensaje excede el límite de bytes")

class TcpServer(private val port: Int) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    
    // Almacena la conexión directa para poder escribirle al socket
    private val activeSockets = ConcurrentHashMap<String, Socket>()

    // 1. Instanciamos el Motor y le pasamos sus "armas" de red
     val gameEngine = GameEngine(
        sendToClient = { id, msg ->
            activeSockets[id]?.let { sendMessage(it, msg) }
        },
        broadcast = { msg ->
            activeSockets.values.forEach { sendMessage(it, msg) }
        },
        closeConnection = { id, error ->
            activeSockets[id]?.let { socket ->
                sendMessage(socket, ErrorMsg(error.name))
                socket.close() // La norma exige cerrar tras el error
            }
            activeSockets.remove(id)
        }
    )

    suspend fun start() = withContext(Dispatchers.IO) {
        coroutineScope {
            running = true
            serverSocket = ServerSocket(port)
            println("Servidor TCP: Escuchando conexiones en el puerto $port...")

            gameEngine.startProcessingLoop(this)

            while (running) {
                try {
                    val socket = serverSocket?.accept() ?: break

                    // Blindaje contra Race Condition de Conexiones Simultáneas
                    if (activeSockets.size >= GameConfig.MAX_PLAYERS) {
                        println("Conexión rechazada: Lobby lleno.")
                        sendMessage(socket, ErrorMsg(ErrorCode.LOBBY_FULL.name))
                        socket.close()
                        continue
                    }

                    // Se registra INMEDIATAMENTE en el hilo principal antes de lanzar la corrutina
                    val connectionId = UUID.randomUUID().toString()
                    activeSockets[connectionId] = socket

                    launch(Dispatchers.IO) { handleClient(socket, connectionId) }

                } catch (e: Exception) {
                    if (running) println("Error aceptando conexión TCP: ${e.message}")
                }
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun handleClient(socket: Socket, connectionId: String) {
        try {
            println("Nuevo cliente conectado desde ${socket.inetAddress.hostAddress} (ID: $connectionId)")

            while (running && !socket.isClosed) {
                // Framing manual seguro contra ataques de memoria (OOM)
                val jsonString = readFramedMessage(socket.inputStream) ?: break 

                try {
                    val message = CtfJson.format.decodeFromString<CtfMessage>(jsonString)
                    
                    // 3. Inyectamos el mensaje al canal del GameEngine
                    gameEngine.receiveMessage(connectionId, message)
                    
                } catch (e: MissingFieldException) {
                    sendMessage(socket, ErrorMsg(ErrorCode.MISSING_FIELD.name))
                } catch (e: SerializationException) {
                    val errorCode = if (e.message?.contains("Class discriminator") == true) {
                        ErrorCode.UNKNOWN_TYPE
                    } else {
                        ErrorCode.INVALID_JSON
                    }
                    sendMessage(socket, ErrorMsg(errorCode.name))
                } catch (e: IllegalArgumentException) {
                    sendMessage(socket, ErrorMsg(ErrorCode.INVALID_JSON.name))
                }
            }
        } catch (e: MessageTooLargeException) {
            println("Cliente $connectionId desconectado: Mensaje demasiado grande.")
            sendMessage(socket, ErrorMsg(ErrorCode.MESSAGE_TOO_LARGE.name))
        } catch (e: Exception) {
            println("Desconexión o error de lectura en $connectionId: ${e.message}")
        } finally {
            socket.close()
            activeSockets.remove(connectionId)
            
            // 4. Notificamos al canal la caída del cliente para limpiar el estado
            gameEngine.notifyDisconnect(connectionId)
            println("Cliente $connectionId desconectado.")
        }
    }

    // Lector manual byte a byte para evitar buffer overflow
    private fun readFramedMessage(input: InputStream): String? {
        val buffer = java.io.ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1) return null 
            if (byte == '\n'.code) break
            buffer.write(byte)
            if (buffer.size() > GameConfig.MESSAGE_MAX_SIZE) {
                throw MessageTooLargeException()
            }
        }
        
        val bytes = buffer.toByteArray()
        val trimmed = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
            bytes.copyOf(bytes.size - 1)
        } else {
            bytes
        }
        return String(trimmed, Charsets.UTF_8)
    }

    private fun sendMessage(socket: Socket, message: CtfMessage) {
        try {
            val json = CtfJson.format.encodeToString(message)
            val output = socket.outputStream
            output.write((json + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (e: Exception) {
            // Ignorar silenciosamente si el socket ya murió
        }
    }

    fun stop() {
        running = false
        serverSocket?.close()
    }
}