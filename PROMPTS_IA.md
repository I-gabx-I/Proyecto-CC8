# Bitácora de Uso de Inteligencia Artificial

## Herramienta utilizada

Claude (Anthropic), usado como asistente durante todo el ciclo de
desarrollo: decisiones de arquitectura, generación y revisión de código,
detección de bugs, y organización de la documentación final.

## Metodología de trabajo

El flujo general fue iterativo: se pedía una pieza de código o una
decisión, la IA la entregaba junto con un análisis de conformidad contra
`SPEC.md`, y cada archivo se revisaba en busca de vulnerabilidades o
incumplimientos normativos antes de darlo por cerrado y avanzar a la
siguiente pieza. Ningún archivo se integró sin antes entender qué hacía y
por qué.

## Prompts e interacciones más relevantes

### 1. Elección de lenguaje y entorno

> "En base a este proyecto, ¿podemos elegir cualquier lenguaje? ¿Por cuál
> te irías en facilidad de programarlo, entenderlo y que funcione sin
> problemas?"

Resultado: comparación de Python, JavaScript/Node, C#, Godot y Java;
decisión final por Kotlin al descartarse Python por cupo de lenguaje ya
lleno en la clase. Se pidió también recomendación de IDE, confirmando
IntelliJ IDEA sobre VS Code por soporte nativo de Kotlin.

### 2. Estructuras del protocolo

> "Ok, empezamos. [Estructura inicial de data classes con
> `kotlinx.serialization`]. ¿Comparas con la sealed class con polimorfismo
> o clases planas?"

Resultado: se detectó que `kotlinx.serialization` omite por defecto los
campos que coinciden con su valor por defecto (`encodeDefaults = true`
necesario), y que el discriminador de tipo polimórfico solo se aplica si
se serializa explícitamente contra el tipo `CtfMessage`, no contra el tipo
concreto. Ambos bugs eran silenciosos (compilaban sin error) y se repitieron
más de una vez en distintos archivos durante el desarrollo.

### 3. Descubrimiento UDP

> "¿Cómo ves este UdpRadar.kt? Evalúa, corrige, busca vulnerabilidades."

Resultado: se identificó que la implementación inicial solo enviaba
`discover` a `255.255.255.255`, sin el broadcast de subred que exige la
sección 1.3 del SPEC — corregido calculando las direcciones de broadcast
de cada interfaz de red local.

### 4. Framing TCP y seguridad

> "Revisa este TcpServer.kt, corrige, busca vulnerabilidades, corrijo."

Resultado: se detectó que `BufferedReader.readLine()` no tiene límite de
tamaño, por lo que un mensaje sin `\n` podía agotar la memoria del
servidor antes de que se validara `message_max_size` — exactamente el
riesgo que la sección 6.2 del SPEC exige mitigar. Se reemplazó por lectura
manual byte a byte con validación de tamaño en cada byte acumulado.

### 5. Condición de carrera en el motor del juego

> "Realizamos esto [GameEngine con procesamiento directo desde corrutinas
> por conexión]. Corrige, busca vulnerabilidades y luego corrijo."

Resultado: se identificó que procesar mensajes directamente desde
corrutinas paralelas por conexión viola el requisito de procesamiento
secuencial del SPEC (sección 2.2) y puede producir más de un
`flag.owner` simultáneo. Se rediseñó con un patrón Actor (canal +
consumidor único). El mismo tipo de bug reapareció después con el *game
loop* físico corriendo en una corrutina separada del canal, y se corrigió
integrándolo también como evento del canal.

### 6. Diagnóstico de un servidor que no respondía

> "Conecté 3 clientes pero ninguno recibe nada del servidor, ¿qué puede
> ser?"

Resultado: diagnóstico guiado por descarte (verificar logs de
`"Jugador aceptado"`, confirmar ejecución nativa vs. Gradle) hasta
identificar que `ServerSocket.accept()` bloqueaba el único hilo de
`runBlocking`, impidiendo que el canal del `GameEngine` recibiera tiempo de
ejecución. Corregido con `withContext(Dispatchers.IO)`.

### 7. Regla de victoria y anti-trampas

> "Aplica los fixes [transición de victoria, no-op del portador, reset si
> todos se desconectan] y pásame el archivo completo."

Resultado: implementación de la transición obligatoria "dentro → fuera"
del círculo antes de declarar victoria, evitando que un jugador ya fuera
del círculo gane instantáneamente al robar la bandera (protección
anti-trampas explícita de la sección 6.4 del SPEC).

### 8. Interfaz gráfica

> "Vamos con la interfaz, mejorémosla — vibra neón/arcade, empezando por
> login y lobby."

Resultado: diseño de una paleta de colores y tema reutilizable
(`NeonTheme.kt`), rediseño de las pantallas de login, lobby, countdown y
game over, y adición de interpolación visual (`Animatable`) entre los
`state` recibidos cada 50 ms para suavizar el movimiento en el canvas.

### 9. Pruebas de red real y documentación

> "Ya probé con hotspot, a todos les detecta automático menos a mí, ¿qué
> podría ser?"

Resultado: diagnóstico de que el firewall de terceros (McAfee) instalado
en la máquina probablemente bloqueaba las respuestas UDP entrantes en la
red del hotspot, clasificada como no confiable — no un bug del código, ya
validado previamente contra otros compañeros.

## Nota sobre verificación

Todo el código generado o corregido con ayuda de la IA fue revisado contra
el texto normativo del `SPEC.md` de la clase antes de integrarse, y
probado manualmente (incluyendo casos de error deliberados vía mensajes
JSON crudos) antes de considerarse parte del proyecto final.
