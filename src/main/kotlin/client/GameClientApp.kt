package client

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.launch
import protocol.Direction
import protocol.InputMsg
import protocol.InteractMsg

fun main() = application {
    // 1. Creamos nuestro estado y cliente
    val gameState = remember { GameState() }
    val client = remember { UiGameClient(gameState) }
    val scope = rememberCoroutineScope()

    // 2. Variables temporales para la pantalla de login
    var ip by remember { mutableStateOf("localhost") }
    var port by remember { mutableStateOf("8889") }
    var name by remember { mutableStateOf("gabx") }

    // 3. Variables para el control fluido (¡Soporta diagonales!)
    val activeKeys = remember { mutableSetOf<Key>() }
    var currentDx by remember { mutableStateOf(0) }
    var currentDy by remember { mutableStateOf(0) }

    // 4. Ventana principal
    Window(
        onCloseRequest = {
            client.disconnect()
            exitApplication()
        },
        title = "Capture the Flag - Cliente Gráfico",
        onKeyEvent = { event ->
            val key = event.key
            
            // Interacción (Presionar E)
            if (key == Key.E && event.type == KeyEventType.KeyDown) {
                client.send(InteractMsg())
                return@Window true
            }

            // Movimiento (W, A, S, D)
            val isMovementKey = key == Key.W || key == Key.A || key == Key.S || key == Key.D
            if (isMovementKey) {
                if (event.type == KeyEventType.KeyDown) activeKeys.add(key)
                else if (event.type == KeyEventType.KeyUp) activeKeys.remove(key)
                
                // Calculamos el vector resultante
                var newDx = 0
                var newDy = 0
                if (activeKeys.contains(Key.A)) newDx -= 1
                if (activeKeys.contains(Key.D)) newDx += 1
                if (activeKeys.contains(Key.W)) newDy -= 1
                if (activeKeys.contains(Key.S)) newDy += 1
                
                // Solo enviamos mensaje al servidor si cambiamos de dirección y estamos en PLAYING
                if ((newDx != currentDx || newDy != currentDy) && gameState.currentPhase == "PLAYING") {
                    currentDx = newDx
                    currentDy = newDy
                    client.send(InputMsg(Direction(newDx, newDy)))
                }
                return@Window true
            }
            false // Ignorar otras teclas
        }
    ) {
        MaterialTheme(colors = NeonColors) {
            // Un simple "Switch" para mostrar la pantalla correcta
            when (gameState.currentPhase) {
                "LOBBY" -> {
                    // Si estamos desconectados, mostramos el login
                    if (gameState.myPlayerId.isEmpty()) {
                        LoginScreen(
                            ip = ip, onIpChange = { ip = it },
                            port = port, onPortChange = { port = it },
                            name = name, onNameChange = { name = it },
                            onConnect = {
                                client.connect(ip, port.toIntOrNull() ?: 8889, name, scope)
                            },
                            errorMsg = gameState.systemMessage
                        )
                    } else {
                        // Si ya nos conectamos, mostramos el Lobby
                        LobbyScreen(gameState)
                    }
                }
                "COUNTDOWN" -> CountdownScreen(gameState.countdownSeconds)
                "PLAYING" -> GameScreen(gameState)
                "GAME_OVER" -> GameOverScreen(gameState)
            }
        }
    }
}

@Composable
fun CountdownScreen(seconds: Int) {
    val scale by animateFloatAsState(
        targetValue = seconds.toFloat(),
        animationSpec = tween(200)
    )

    Box(
        modifier = Modifier.fillMaxSize().background(NeonPalette.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "LA PARTIDA EMPIEZA EN",
                color = NeonPalette.TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "$seconds",
                color = if (seconds <= 1) NeonPalette.Magenta else NeonPalette.Cyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 96.sp,
                modifier = Modifier.graphicsLayer(scaleX = 0.85f + (scale % 1f) * 0.15f, scaleY = 0.85f + (scale % 1f) * 0.15f)
            )
        }
    }
}

@Composable
fun GameOverScreen(state: GameState) {
    val winnerId = state.lastWinnerId
    val winnerName = state.lobbyPlayers.find { it.id == winnerId }?.name ?: winnerId.take(8)
    val iWon = winnerId == state.myPlayerId

    Box(
        modifier = Modifier.fillMaxSize().background(NeonPalette.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (iWon) "¡VICTORIA!" else "PARTIDA TERMINADA",
                color = if (iWon) NeonPalette.Green else NeonPalette.Magenta,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 40.sp
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Ganador: $winnerName",
                color = NeonPalette.TextPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "Regresando al lobby...",
                color = NeonPalette.TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
fun GameScreen(state: GameState) {
    val gameState = state.currentGameState ?: return
    val config = state.config ?: return

    // Un Animatable de posición por jugador, persistido entre recomposiciones
    val animatedPositions = remember { mutableStateMapOf<String, Animatable<Offset, AnimationVector2D>>() }
    val animatedFlag = remember { Animatable(Offset(config.map_size.toFloat() / 2, config.map_size.toFloat() / 2), Offset.VectorConverter) }

    // Cada vez que llega un StateMsg nuevo, animamos suavemente hacia la nueva posición
    LaunchedEffect(gameState) {
        gameState.players.forEach { player ->
            val target = Offset(player.x.toFloat(), player.y.toFloat())
            val anim = animatedPositions.getOrPut(player.id) { Animatable(target, Offset.VectorConverter) }
            launch { anim.animateTo(target, animationSpec = tween(50, easing = LinearEasing)) }
        }
        // Limpia jugadores que ya no están conectados
        val activeIds = gameState.players.map { it.id }.toSet()
        animatedPositions.keys.filter { it !in activeIds }.forEach { animatedPositions.remove(it) }

        val flagTarget = Offset(gameState.flag.x.toFloat(), gameState.flag.y.toFloat())
        launch { animatedFlag.animateTo(flagTarget, animationSpec = tween(50, easing = LinearEasing)) }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val scale = minOf(size.width / config.map_size.toFloat(), size.height / config.map_size.toFloat())
        val offsetX = (size.width - config.map_size.toFloat() * scale) / 2f
        val offsetY = (size.height - config.map_size.toFloat() * scale) / 2f

        withTransform({
            translate(left = offsetX, top = offsetY)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            // Fondo de arena
            drawRect(
                color = NeonPalette.Surface,
                size = Size(config.map_size.toFloat(), config.map_size.toFloat())
            )

            // Zona de victoria (círculo central)
            drawCircle(
                color = NeonPalette.Green.copy(alpha = 0.12f),
                radius = config.circle_radius.toFloat(),
                center = Offset(config.map_size.toFloat() / 2, config.map_size.toFloat() / 2)
            )
            drawCircle(
                color = NeonPalette.Green.copy(alpha = 0.5f),
                radius = config.circle_radius.toFloat(),
                center = Offset(config.map_size.toFloat() / 2, config.map_size.toFloat() / 2),
                style = Stroke(width = 2f)
            )

            // Bandera libre (usa posición interpolada)
            if (gameState.flag.owner == null) {
                drawCircle(
                    color = Color(0xFFFFEA00), // dorado neón, distinto de cian/magenta
                    radius = config.interact_radius.toFloat() * 0.4f,
                    center = animatedFlag.value
                )
            }

            // Jugadores (usan posición interpolada, no la cruda del StateMsg)
            gameState.players.forEach { player ->
                val pos = animatedPositions[player.id]?.value
                    ?: Offset(player.x.toFloat(), player.y.toFloat())
                val isMe = player.id == state.myPlayerId
                val isFlagCarrier = player.id == gameState.flag.owner

                drawCircle(
                    color = if (isMe) NeonPalette.Cyan else NeonPalette.Magenta,
                    radius = config.player_radius.toFloat(),
                    center = pos
                )

                if (isFlagCarrier) {
                    drawCircle(
                        color = Color(0xFFFFEA00),
                        radius = config.player_radius.toFloat() + 5f,
                        center = pos,
                        style = Stroke(width = 4f)
                    )
                }
            }
        }
    }
}