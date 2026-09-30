# HikariRO Móvil — Diagnóstico de arranque (29-09-2026)

## 1. Resuelto: libxcb con la ruta del paquete original (rootfs v28)
- `usr/lib/libxcb.so.1.1.0` tenía grabado `/data/data/com.winlator/files/rootfs/tmp/.X11-unix/X` → XOpenDisplay fallaba → nodrv. La "carrera de hilos en Wine" era solo una consecuencia.
- RootFSInstaller parchea libxcb, libX11, libXcursor y libfontconfig con el prefijo corto.
- `HikariOptionInfoFixer` ajusta savedata/OptionInfo.lua (venía con 1768x992 del PC) a la resolución del contenedor.

## 2. Resuelto: la v28 corrompía libfontconfig (rootfs v29)
- HardcodedPathPatcher ya nunca cambia el tamaño de un ELF.

## 3. Resuelto: Gladio tumbaba la app al pintar
- SIGSEGV en `libgladiorenderer.so` (glGetTexImage). El contenedor usa VirGL (`HikariConfig.OPENGL_DRIVER`).
- **Hito: login OK y se llega a la selección de personaje.**

## 4. Usabilidad
- Botón flotante de teclado (arriba a la derecha) → `AppUtils.showKeyboard`.
- Cerrar el juego cierra la app (`finishAndRemoveTask` + exit) en vez de reiniciarla en bucle.
- Pantalla "Cargando HikariRO..." encima de todo hasta que la ventana de raghikari.exe (≥800 px de ancho) recibe su primer dibujado; tope de 3 min.

## 5. Rendimiento
- Causa principal del lag: se había quedado activo el modo diagnóstico: WINEDEBUG `+err,+file,+loaddll,+module,+win,+system` (traza cada lectura de .grf), Box64 con logs al máximo y todo volcado a Logcat (~450 MB en 90 s).
- `HikariConfig.DIAGNOSTIC_MODE = false` por defecto → WINEDEBUG `-all`, Box64 sin logs, salida a /dev/null. Para depurar: ponerlo a `true` y recompilar.
- Preset Box64: CONSERVATIVE → INTERMEDIATE (`HikariConfig.BOX64_PRESET`), autocorregido en el contenedor existente.

## Pendiente
- Riesgo: nProtect GameGuard dentro de raghikari.exe (el login funciona, parece no bloquear).
- Decidir si se mantiene el forzado a Windows XP.
