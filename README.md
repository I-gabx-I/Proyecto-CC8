# Captura la Bandera — CC8 2026

Implementación en Kotlin del protocolo estándar de Captura la Bandera
acordado por la clase CC8 2026 (`SPEC.md` v1.2.0, protocolo `v=1`). El
proyecto puede operar como **servidor** (autoridad de la partida) o como
**cliente** (interfaz gráfica jugable), cumpliendo el requisito de
interoperabilidad con proyectos de otros lenguajes de la clase.

## Requisitos

- JDK 21 (recomendado; Compose Desktop está probado y documentado
  principalmente sobre versiones LTS).
- IntelliJ IDEA (Community o superior).
- Conexión de red local (LAN, hotspot compartido, o VPN tipo LAN como
  Radmin/Hamachi/ZeroTier) para probar con otras máquinas.

## Cómo correr el servidor

Ejecutar `src/main/kotlin/Main.kt`.

Levanta automáticamente:
- **TCP 8889** — partida (unirse, moverse, interactuar, recibir estado).
- **UDP 8888** — descubrimiento por broadcast.

```
=== Servidor CTF iniciado ===
TCP puerto: 8889 | UDP descubrimiento: 8888
```

## Cómo correr el cliente

**Interfaz gráfica (recomendado):** ejecutar
`src/main/kotlin/client/GameClientApp.kt`. Permite:
- Descubrimiento automático de servidores en la red local.
- Conexión manual por `IP:puerto` como respaldo (necesario en redes con
  aislamiento de cliente o broadcast bloqueado, p. ej. algunos hotspots
  móviles).

**Cliente de consola (para pruebas y depuración):** ejecutar
`src/main/kotlin/ConsoleTestClient.kt`. Soporta movimiento con
`w`/`a`/`s`/`d`, interacción con `e`, y envío de JSON crudo con
`raw <mensaje>` — útil para probar manualmente casos de error del
protocolo (JSON malformado, campos faltantes, versiones incompatibles,
etc.).

> **Nota:** para correr un cliente contra un servidor en la misma máquina,
> usar `localhost` en vez de la IP de red — algunos hotspots no soportan
> loopback/NAT hairpin hacia la propia IP pública del dispositivo.

## Estructura del proyecto

```
src/main/kotlin/
├── protocol/    → Mensajes JSON, catálogo de errores, constantes del juego
├── network/     → Sockets TCP/UDP, framing, descubrimiento
├── engine/      → Máquina de estados, física, validaciones del servidor
├── client/      → Interfaz gráfica (Compose Desktop)
├── Main.kt               → Punto de entrada del servidor
└── ConsoleTestClient.kt   → Cliente de consola para pruebas
```

## Documentación adicional

- [`DOCUMENTACION.md`](./DOCUMENTACION.md) — arquitectura, decisiones de
  diseño y bugs encontrados/resueltos durante el desarrollo.
- [`PROMPTS_IA.md`](./PROMPTS_IA.md) — bitácora de uso de inteligencia
  artificial durante el desarrollo.

## Estado de conformidad con el estándar

- ✅ Transporte híbrido TCP/UDP con sockets básicos del lenguaje (sin
  librerías externas de conexión).
- ✅ Framing por línea (`\n`), con protección contra mensajes sin
  delimitador (previene agotamiento de memoria).
- ✅ Descubrimiento por broadcast dual + respaldo manual.
- ✅ Máquina de estados completa (`Lobby → Countdown → Playing → Game Over
  → Pausa → Lobby`), servidor 100% autoritativo.
- ✅ Validación de la transición de victoria "dentro → fuera" del círculo
  (anti-trampas).
- ✅ Catálogo de errores normativo con cierre de conexión según
  corresponda.
- ✅ Soporte para hasta 100 jugadores simultáneos.
