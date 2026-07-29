# Documentación de Implementación — Proyecto CTF (CC8 2026)

## 1. Descripción general

Implementación en Kotlin del protocolo estándar de Captura la Bandera acordado
por la clase CC8 2026 (`SPEC.md` v1.2.0, protocolo `v=1`). El proyecto puede
operar como servidor (autoridad de la partida) o como cliente (interfaz
gráfica jugable), cumpliendo el requisito de interoperabilidad con proyectos
de otros lenguajes de la clase.

## 2. Arquitectura general

El proyecto se organiza en cuatro capas con responsabilidades separadas:

```
protocol/   → Contrato de red: mensajes, errores, constantes del juego
network/    → Transporte: sockets TCP/UDP, framing, descubrimiento
engine/     → Autoridad: máquina de estados, física, validaciones
client/     → Presentación: interfaz gráfica (Compose Desktop)
```

Esta separación permite que `engine/GameEngine.kt` no sepa nada de sockets
(recibe y emite únicamente objetos `CtfMessage` a través de callbacks), y que
`network/` no sepa nada de reglas del juego. Esto facilita razonar sobre cada
capa por separado y es lo que hizo posible depurar los bugs de concurrencia
descritos en la sección 4.

### 2.1 Componentes principales

- **`protocol/Messages.kt`** — Los 12 mensajes del catálogo como `sealed
  class CtfMessage`, serializados con `kotlinx.serialization` usando
  polimorfismo por `classDiscriminator = "type"`.
- **`protocol/ErrorCode.kt`** — Enum con los 11 códigos normativos de error.
- **`protocol/GameConfig.kt`** — Constantes inmutables (`map_size`,
  `circle_radius`, `speed`, `tick_rate`, límites de red, etc.), tomadas
  literalmente de la tabla de constantes del SPEC.
- **`network/UdpRadar.kt`** — `UdpServer` (escucha `discover` en el puerto
  8888, responde `server_info` con el estado real del `GameEngine`) y
  `UdpClient` (broadcast dual + escaneo con timeout).
- **`network/TcpServer.kt`** — Acepta conexiones TCP, hace framing manual
  byte a byte, y traduce mensajes JSON a llamadas sobre el `GameEngine`.
- **`engine/GameEngine.kt`** — Máquina de estados (`LOBBY → COUNTDOWN →
  PLAYING → POST_GAME → LOBBY`), física del jugador, validaciones de
  captura/robo/victoria. Es la única autoridad de la partida.
- **`client/`** — `GameState` (estado observable), `UiGameClient` (traduce
  mensajes de red a mutaciones de estado), `GameClientApp` (UI declarativa
  en Compose: login, lobby, countdown, canvas del juego, game over).

## 3. Cómo se logra la comunicación entre proyectos

### 3.1 Descubrimiento (UDP, puerto 8888)

El cliente no conoce la IP del servidor de antemano, así que:

1. Envía `{"type":"discover","v":1}` por broadcast a **dos** destinos:
   `255.255.255.255` y el broadcast calculado de su propia subred (obtenido
   vía `NetworkInterface.interfaceAddresses`, sin cálculo manual de máscaras).
   Esto es necesario porque muchos routers no reenvían la dirección
   limitada `255.255.255.255`.
2. El socket UDP del cliente habilita `SO_BROADCAST` antes de enviar.
3. El servidor escucha con `SO_REUSEADDR`, valida que `v == 1` (descarta
   silenciosamente cualquier otro valor, según el SPEC), y responde por
   *unicast* directamente a la IP de origen del paquete con `server_info`
   (nombre, puerto TCP, estado real de la partida, cantidad de jugadores).
4. Como respaldo, el cliente permite conexión manual por `IP:puerto`, para
   redes donde el broadcast esté bloqueado (ver sección 5, "aislamiento de
   cliente" en hotspots móviles).

### 3.2 Partida (TCP, puerto dinámico anunciado por el servidor)

1. El cliente abre **una única conexión TCP** hacia el `tcp_port` recibido
   en `server_info` y la mantiene abierta durante toda la partida.
2. **Framing:** cada mensaje es una línea JSON terminada en `\n`. La lectura
   se hace **byte a byte** (no con `BufferedReader.readLine()`), acumulando
   en un buffer y validando el tamaño en cada byte recibido — esto es
   deliberado: `readLine()` no tiene límite de tamaño, así que un mensaje sin
   `\n` podría agotar la memoria del servidor antes de que el chequeo de
   `message_max_size` (64 KB) llegara a ejecutarse (sección 6.2 del SPEC).
   Un mensaje que supera el límite se rechaza con `MESSAGE_TOO_LARGE` y la
   conexión se cierra.
3. **Envío:** se escribe explícitamente `json + "\n"` sobre el
   `OutputStream`, en vez de usar `PrintWriter.println()` — ese método usa
   el separador de línea del sistema operativo, que en Windows es `\r\n`, y
   el SPEC exige que el emisor nunca incluya `\r`.
4. El servidor es servidor-autoritativo: valida todas las reglas del
   dominio (fase, movimiento, captura, robo, victoria) y el cliente
   únicamente envía intenciones (`input`, `interact`) y renderiza el estado
   recibido.

## 4. Decisiones de diseño clave y bugs resueltos

Esta sección documenta problemas reales encontrados durante el desarrollo,
no hipotéticos — son parte del proceso iterativo de revisión de código.

### 4.1 Condición de carrera entre conexiones concurrentes (la más crítica)

**Problema:** `TcpServer` lanza una corrutina independiente por cada cliente
conectado. La primera versión de `GameEngine` procesaba los mensajes
directamente desde esas corrutinas, lo que significa que dos jugadores
mandando `interact` casi al mismo tiempo podían ejecutar el check-then-act
de "¿está libre la bandera? → asignar" en paralelo, en hilos distintos —
violando la sección 2.2 del SPEC ("el servidor procesa los mensajes
entrantes de a uno, en el orden en que llegan") y potencialmente rompiendo
la invariante de que solo puede existir un `flag.owner` no nulo a la vez.

**Solución — patrón Actor con canal serializado:** todos los eventos que
tocan el estado del juego (mensajes de jugador, desconexiones, y los
"ticks" periódicos del reloj de física/countdown) se encolan en un
`Channel<InboxEvent>` y son consumidos por **una única corrutina**. Esto
garantiza que el estado compartido (`players`, `flagOwner`, `phase`) solo
se lee y escribe desde un hilo lógico a la vez, sin necesidad de locks
explícitos.

Una segunda iteración de este mismo bug apareció cuando el *game loop*
físico (movimiento a 20 Hz) se implementó como una corrutina **separada**
del canal — volviendo a introducir la misma clase de carrera sobre
`player.x`/`player.y`. Se corrigió convirtiendo cada tick también en un
evento del canal (`InboxEvent.Tick`), de forma que el "productor" (un
`delay(50ms)` en una corrutina aparte) solo dispara el evento, pero el
*consumo* real (mover jugadores, evaluar victoria) ocurre siempre en el
mismo consumidor único.

### 4.2 Bloqueo del hilo principal por operación bloqueante en `runBlocking`

**Problema:** `Main.kt` usa `runBlocking`, que por defecto corre en un solo
hilo. `ServerSocket.accept()` es una llamada bloqueante de Java (no
`suspend`). Como la corrutina consumidora del canal heredaba ese mismo
hilo único, quedaba agendada pero nunca podía ejecutar mientras `accept()`
estuviera bloqueado esperando una conexión — resultando en clientes que se
conectaban exitosamente por TCP pero nunca recibían respuesta alguna del
`GameEngine` (ni `welcome`, ni errores).

**Solución:** `TcpServer.start()` se envuelve en
`withContext(Dispatchers.IO)`, moviendo tanto el `accept()` bloqueante como
el consumidor del canal al pool de hilos de IO, liberando al hilo principal
de `runBlocking`.

### 4.3 Transición de victoria obligatoria (anti-trampas)

**Problema:** una implementación ingenua dispara `game_over` en cuanto
detecta que el portador está a más de 315 unidades del centro — pero esto
permite que alguien que ya estaba fuera del círculo robe la bandera y gane
instantáneamente, sin haber hecho el recorrido, violando la sección 3.3
("quien roba estando ya fuera NO gana al instante") y la protección
anti-trampas explícita de la sección 6.4.

**Solución:** se mantiene una bandera de estado
(`flagCarrierInsideCircle`) que registra si el portador actual estuvo
dentro del círculo desde que tomó la bandera. La victoria solo se dispara
en la transición real `dentro → fuera`, evaluada en cada tick antes de
procesar interacciones pendientes (orden exigido por la sección 4.1 del
SPEC).

### 4.4 Discriminador de tipo omitido por `kotlinx.serialization`

**Problema:** al serializar un mensaje declarando el tipo estático concreto
(p. ej. `encodeToString(discoverMsg)` donde `discoverMsg: DiscoverMsg`), la
librería no aplica el discriminador polimórfico configurado en
`CtfMessage`, produciendo JSON sin el campo `"type"` — un incumplimiento
directo del requisito normativo de que todo mensaje debe incluirlo. El bug
es silencioso: el código compila y no lanza excepción, solo produce salida
incorrecta. Ocurrió dos veces de forma independiente: una en el servidor
UDP (`UdpServer`) y otra en la respuesta al descubrimiento.

**Solución:** todo punto de serialización usa explícitamente el genérico
`encodeToString<CtfMessage>(mensaje)`, forzando a la librería a usar el
serializador polimórfico de la clase base en vez del serializador propio
del tipo concreto.

### 4.5 Otros ajustes normativos aplicados durante revisión de código

- Redondeo de posiciones a 1 decimal con `RoundingMode.HALF_UP` sobre
  `BigDecimal`, en vez de `kotlin.math.round` (que usa redondeo bancario
  *ties-to-even*, incompatible con el "half-away-from-zero" exigido).
- Validación explícita de `dir.x`/`dir.y` en `{-1, 0, 1}` → `INVALID_FIELD`
  si están fuera de rango (sección 6.4).
- Clamp de posición del jugador al rango `[15, 985]` en cada tick (tabla
  4.1 / sección 3.3).
- `interact` del propio portador es un no-op explícito (sección 5.3) — una
  versión temprana lo trataba incorrectamente como "soltar la bandera",
  mecánica que el estándar no define.
- Reinicio a `LOBBY` si todos los jugadores se desconectan durante
  `PLAYING`/`POST_GAME` (tabla de la sección 5.2), evitando que el servidor
  quede en un estado sin jugadores del que nunca sale.

## 5. Pruebas de interoperabilidad realizadas

- Pruebas locales con múltiples instancias de cliente (consola y gráfico)
  contra el mismo servidor, cubriendo el flujo completo: descubrimiento →
  join → lobby → countdown → movimiento → captura → robo → victoria →
  regreso a lobby sin reconexión.
- Casos de borde probados manualmente vía comando `raw <json>` en el
  cliente de consola: `INVALID_JSON`, `MISSING_FIELD`, `VERSION_MISMATCH`,
  `GAME_STARTED`, `INVALID_FIELD` en `dir` fuera de rango.
- Pruebas en red real (no localhost) usando hotspot móvil compartido entre
  varios compañeros de clase, confirmando que el descubrimiento por
  broadcast y la conexión TCP funcionan entre máquinas distintas.
- **Hallazgo relevante:** en algunos hotspots móviles, el descubrimiento
  automático falló para un participante específico mientras el resto de la
  clase se detectaba con normalidad entre sí. Se determinó que la causa más
  probable era software de firewall de terceros (no el Firewall de Windows)
  bloqueando las respuestas UDP entrantes en la red del hotspot,
  clasificada como no confiable por defecto. La conexión manual por
  `IP:puerto` (requisito obligatorio del estándar, sección 1.3) funcionó
  como respaldo en todos los casos donde el broadcast no llegó.

## 6. Herramientas y entorno

- Lenguaje: Kotlin (JVM), elegido por robustez de `java.net` para sockets
  crudos, tipado fuerte para las estructuras del protocolo, y soporte de
  corrutinas para concurrencia sin manejo manual de hilos.
- Serialización: `kotlinx.serialization` (JSON).
- Interfaz gráfica: Jetpack Compose Desktop (Compose Multiplatform).
- IDE: IntelliJ IDEA Community.
- Control de versiones: Git / GitHub.
