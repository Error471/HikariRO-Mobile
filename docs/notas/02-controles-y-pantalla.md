# HikariRO Móvil — Controles, pantalla y rendimiento (29-09-2026)

Estado: el juego entra al servidor y se puede jugar con DXVK. F1-F9 y los gestos funcionan.

## Pantalla
- Resolución calculada según el móvil: alto fijo `HikariConfig.GAME_HEIGHT = 600`, ancho según la proporción real de la pantalla (múltiplo de 8). Ej.: 2400x1080 → 1336x600.
- Se aplica al escritorio del contenedor (autocorregido) y a OptionInfo.lua.
- `HikariConfig.GAME_FULLSCREEN = true` → OptionInfo `ISFULLSCREENMODE = 1`. Si da problemas, ponerlo a false.

## Gestos (TouchpadView, solo contenedor HikariRO)
- Tocar = clic izquierdo. Mantener 0,4 s (vibra) y mover = arrastrar. Pellizcar = zoom (rueda).
- `cancelLongPressDrag()` (no `cancelLongPress`, que choca con View de Android).

## Perfil de controles `assets/inputcontrols/hikariro.icp` (id 100)
- Se instala y se muestra solo. Para reinstalarlo tras cambiarlo: subir `HikariConfig.CONTROLS_VERSION`.
- Abajo a la derecha: atajos (con páginas) y "Clic D". Izquierda: Esc, Enter, Sentar (Insert); Alt, Ctrl, Shift como interruptores.

## Rendimiento en mazmorras
- Con WineD3D: hilo principal raghikari.exe 85-90% de un núcleo, `wined3d_cs` ~60%, hilo VirGL ~22%. Cuello de botella: wined3d → OpenGL → VirGL.
- Cambio: `HikariConfig.DX_WRAPPER = DXVK` (Direct3D 9 → Vulkan con Turnip / Vortek). VirGL sigue como driver OpenGL (ddraw vía wined3d).

## Vulkan: dos rutas del paquete original (arreglado en XServerDisplayActivity.fixVulkanIcdPaths)
1. `usr/share/vulkan/icd.d/freedreno_icd.aarch64.json` → `library_path` con `/data/data/com.winlator/...`. Se reescribe con la ruta real.
2. `usr/lib/libvulkan.so.1.3.301` (el cargador) busca icd.d por defecto en `/data/data/com.winlator/files/rootfs/usr/share`. Se exportan `VK_ICD_FILENAMES` y `VK_DRIVER_FILES` con los .json de icd.d.
- Si DXVK falla: `DIAGNOSTIC_MODE = true` y capturar log, o usar el modo de compatibilidad (WineD3D).

## Salida del juego
- "Volver a Windows" (menú Esc): raghikari.exe lanza una excepción C++ `0x80000100` durante su propio cierre y se queda en la ventana "Gravity(tm) Error Handler" sin terminar.
- `XServerDisplayActivity.watchGravityErrorHandler()`: al detectar una ventana con ese título, cierra la app a los 0,5 s. También cerraría la app si el cliente fallara de verdad en mitad de la partida.
