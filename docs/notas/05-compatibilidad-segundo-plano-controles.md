# HikariRO Móvil — Compatibilidad, segundo plano, controles, FPS y limpieza (29/30-09-2026)

## 1. Compatibilidad entre móviles — `hikariro/HikariCompat.java`
- Nivel 0 = Vulkan + DXVK (Turnip en Adreno 6xx-8xx, Vortek en el resto). Nivel 1 = OpenGL (VirGL) + WineD3D.
- La primera vez se detecta el nivel: sin Adreno y sin Vulkan 1.1 se empieza en el nivel 1.
- Bajada sugerida, no automática:
  - `markGameStarting` (al aparecer raghikari) deja "pendiente" y `markGameVisible` (10 s después de verse el juego) lo borra.
  - Si la app muere 2 veces seguidas con el pendiente puesto, se pregunta al jugador si quiere el modo de compatibilidad.
  - Si ya estaba en el nivel 1, muestra "No se ha podido abrir HikariRO" con los datos del móvil.
- Revisión 2 (`hikari_compat_revision`): deshace bajadas automáticas de versiones anteriores.
- Salir desde el menú no cuenta como fallo (`clearPending`).
- Avisos de un solo uso: poca RAM, Android < 10, GPU sin Vulkan 1.1.
- En Logcat (etiqueta HikariRO) queda el modelo, la GPU, Vulkan y la RAM.
- El menú de ajustes permite forzar el modo de compatibilidad o volver al normal (se aplica en el siguiente arranque).

## 2. Segundo plano
- `HikariConfig.BACKGROUND_KEEP_ALIVE = true`: al salir de la app, el juego sigue conectado (Winlator congelaba los procesos con SIGSTOP).
- La notificación del ForegroundService solo aparece mientras la app está en segundo plano (`ProcessLifecycleOwner`, observador registrado en `startSession`). Al tocarla se vuelve al juego.
- En segundo plano se deja de dibujar y se silencia el audio (`ALSAClient.setMuted`).
- A los 15 min fuera (`BACKGROUND_SUSPEND_AFTER_MINUTES`) se congela el juego y se suelta el wakelock.

## 3. Controles
- Botón de engranaje, botón Atrás y toque con 4 dedos abren el menú: Editar botones (ControlsEditorActivity en estilo Hikari), Restablecer botones, Vibración al pulsar, Modo gráfico de compatibilidad y Salir del juego.
- Páginas de atajos: campo `page` en los elementos del .icp.
  - Página 1: F1-F9. Página 2: Inv (Alt+E), Equ (Alt+Q), Hab (Alt+S), Info (Alt+V), Grupo (Alt+Z), Clan (Alt+G), Amigos (Alt+H), Misión (Alt+U) y F12.
  - Se cambia deslizando sobre los atajos o tocando los puntos de encima. Los botones con página envían la tecla al soltar.
  - `CONTROLS_VERSION = 3`.
- Vibración: `performHapticFeedback` (VIRTUAL_KEY).
- El teclado automático (hikari_focus.exe) se probó y se eliminó; el teclado se abre con el botón flotante.

## 4. Límite de FPS — DESACTIVADO (`HikariConfig.MAX_FPS = 0`)
- Con `MAX_FPS > 0` se escribe `framerate` en la configuración de DXVK (`d3d9.maxFrameRate`).
- Con 30 el login iba lentísimo (CPU casi parada, solo el hilo `dxvk-frame` trabajando). También se quitó un limitador en VirGL que hundía los FPS.

## 5. Limpieza de Winlator
- Borradas MainActivity, fragments de contenedores, atajos, ajustes, controles y gestor de archivos, ExternalControllerBindingsActivity y lo que solo usaban ellas.
- Constantes movidas a `core/AppDefaults.java`.
- Assets quitados: gladio, zink, d7vk, cnc-ddraw, vkd3d (no se extrae en HikariRO) y los perfiles de controles de Winlator.

## URL de descarga
- `scripts/cambiar_repo_descarga.ps1` escribe `hikari.properties` (`download_base_url`); acepta URL de descarga, URL `.../releases/tag/X` o `usuario/repo@etiqueta`, y comprueba `manifest.json`.
- `app/build.gradle` la pasa a `resValue string hikari_download_base_url`, que lee `HikariConfig.getDownloadBaseUrl(context)`. Hay que recompilar.

## Incidencias resueltas
- Cierre al arrancar (`ForegroundServiceDidNotStartInTimeException`): al pasar del lanzador al juego la app parecía en segundo plano y el servicio se paraba antes de `startForeground`. Arreglado.
- Gradle "Out of memory": `org.gradle.jvmargs=-Xmx4g` en `gradle.properties`.
