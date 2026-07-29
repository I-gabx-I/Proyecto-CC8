package client

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.*

// Definiciones de Paleta de Colores Neon
object NeonPalette {
    val Background = Color(0xFF0A0E27)
    val Surface = Color(0xFF1A1F3A)
    val SurfaceLight = Color(0xFF252B45)
    val TextPrimary = Color(0xFFE0E0FF)
    val TextSecondary = Color(0xFF9090B0)
    val Cyan = Color(0xFF00D4FF)
    val Magenta = Color(0xFFFF00FF)
    val Green = Color(0xFF00FF00)
    val ErrorRed = Color(0xFFFF1744)
}

// Estilos de Texto
val TitleStyle = TextStyle(
    color = NeonPalette.Cyan,
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 20.sp
)

// Colores Material Theme para componentes automáticos
val NeonColors = darkColors(
    primary = NeonPalette.Cyan,
    secondary = NeonPalette.Magenta,
    surface = NeonPalette.Surface,
    background = NeonPalette.Background,
    error = NeonPalette.ErrorRed,
    onPrimary = NeonPalette.Background,
    onSecondary = NeonPalette.Background,
    onSurface = NeonPalette.TextPrimary,
    onBackground = NeonPalette.TextPrimary
)

// Constante para el máximo de jugadores mostrado
const val GameConfigDisplayMax = "100"

@Composable
fun LoginScreen(
    ip: String, onIpChange: (String) -> Unit,
    port: String, onPortChange: (String) -> Unit,
    name: String, onNameChange: (String) -> Unit,
    onConnect: () -> Unit,
    errorMsg: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NeonPalette.Background),
        contentAlignment = Alignment.Center
    ) {
        // Halo detrás del título para efecto de brillo neón
        Text(
            "CAPTURE THE FLAG",
            style = TitleStyle.copy(fontSize = 34.sp),
            modifier = Modifier
                .offset(y = (-160).dp)
                .blur(18.dp)
        )

        Card(
            modifier = Modifier.width(360.dp),
            backgroundColor = NeonPalette.Surface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.5.dp, NeonPalette.Cyan.copy(alpha = 0.5f)),
            elevation = 12.dp
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "CAPTURE THE FLAG",
                    style = TitleStyle.copy(fontSize = 22.sp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Conexión al servidor",
                    color = NeonPalette.TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(24.dp))

                NeonTextField(ip, onIpChange, "IP DEL SERVIDOR")
                Spacer(Modifier.height(12.dp))
                NeonTextField(port, onPortChange, "PUERTO TCP")
                Spacer(Modifier.height(12.dp))
                NeonTextField(name, onNameChange, "TU NOMBRE")
                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = NeonPalette.Cyan,
                        contentColor = NeonPalette.Background
                    )
                ) {
                    Text("CONECTAR", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }

                if (errorMsg.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        errorMsg,
                        color = NeonPalette.ErrorRed,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun NeonTextField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
        textStyle = TextStyle(color = NeonPalette.TextPrimary, fontFamily = FontFamily.Monospace),
        colors = TextFieldDefaults.outlinedTextFieldColors(
            focusedBorderColor = NeonPalette.Cyan,
            unfocusedBorderColor = NeonPalette.TextSecondary.copy(alpha = 0.4f),
            cursorColor = NeonPalette.Cyan
        )
    )
}

@Composable
fun LobbyScreen(state: GameState) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(
        modifier = Modifier.fillMaxSize().background(NeonPalette.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.width(400.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("SALA DE ESPERA", style = TitleStyle.copy(fontSize = 24.sp))
            Spacer(Modifier.height(4.dp))
            Text(
                "${state.lobbyPlayers.size} / $GameConfigDisplayMax jugadores",
                color = NeonPalette.Magenta,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(20.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
            ) {
                items(state.lobbyPlayers) { player ->
                    val isMe = player.id == state.myPlayerId
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        backgroundColor = if (isMe) NeonPalette.SurfaceLight else NeonPalette.Surface,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isMe) NeonPalette.Cyan else NeonPalette.TextSecondary.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(NeonPalette.Green, RoundedCornerShape(5.dp))
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                player.name,
                                color = NeonPalette.TextPrimary,
                                fontFamily = FontFamily.Monospace
                            )
                            if (isMe) {
                                Spacer(Modifier.weight(1f))
                                Text("TÚ", color = NeonPalette.Cyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                if (state.lobbyPlayers.size < 2) "Esperando más jugadores..." else "Iniciando partida...",
                color = NeonPalette.TextSecondary.copy(alpha = pulseAlpha),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
        }
    }
}