# HikariRO para Android

App Android que ejecuta el cliente de **HikariRO** (Ragnarok Online) en el móvil, con controles táctiles. Es un fork de [Winlator](https://github.com/brunodev85/winlator-app) (Wine 10.10 + Box64) reducido a una sola cosa: arrancar HikariRO.

- Paquete: `com.hikarimovil.launcher` (se instala aparte de Winlator).
- Solo ARM64 (`arm64-v8a`), Android 8 o superior (recomendado Android 10+ y 4 GB de RAM).
- Licencia: la de Winlator (GPL-3.0, ver `LICENSE`).

## Qué hace la app al abrirla

1. Instala el sistema base de Winlator (rootfs) y crea un contenedor de Wine ya configurado para HikariRO.
2. Si el cliente no está en el móvil, lo **descarga sola** del release de GitHub (unos 5 GB, con reanudación) y lo extrae.
3. Arranca el patcher (`HikariRO.exe`, Thor Patcher). Al pulsar **Jugar**, el patcher lanza `raghikari.exe`.
4. Muestra una pantalla de carga propia hasta que se ve el juego y, a partir de ahí, los controles táctiles.

## Compilar

1. Android Studio → **File → Open** → la carpeta raíz de este repositorio.
2. Deja que sincronice. Pedirá instalar lo que falte: SDK 35, NDK `24.0.8215888`, CMake `3.22.1`.
3. **Build → Clean Project**, después **Build → Assemble Project**, y **Run ▶** con el móvil conectado por USB (depuración USB activada).

Notas:

- `gradle.properties` ya da 4 GB de memoria a Gradle (los assets pesan mucho). Si falta memoria, sube `-Xmx`.
- Las APK de depuración salen en `app/build/outputs/apk/debug/`.
- Para probar una instalación desde cero hay que borrar los datos de la app o desinstalarla.

## Estructura

| Ruta | Contenido |
|---|---|
| `app/src/main/java/com/winlator/hikariro/` | Todo lo propio de HikariRO (ver abajo) |
| `app/src/main/java/com/winlator/XServerDisplayActivity.java` | Pantalla del juego: carga, menú, botones flotantes, segundo plano, ayudantes de Wine |
| `app/src/main/assets/inputcontrols/hikariro.icp` | Perfil de controles táctiles (subir `CONTROLS_VERSION` al cambiarlo) |
| `app/src/main/assets/hikari/` | Ilustraciones de carga, Wine Gecko y los ayudantes `.exe` |
| `hikari_tools/` | Código fuente en C de los ayudantes de Windows |
| `hikari.properties` | URL del release desde el que se descarga el cliente |
| `scripts/` | Scripts de PowerShell (release, URL de descarga, diagnóstico por adb) |
| `docs/notas/` | Notas de desarrollo: problemas encontrados y cómo se resolvieron |

### Clases principales (`com.winlator.hikariro`)

- `HikariConfig`: todas las constantes (exe, resolución, FPS, segundo plano, controles, lista de ficheros del cliente).
- `HikariLauncherActivity`: arranque (permisos, sistema base, contenedor, descarga y lanzamiento).
- `HikariClientDownloader`: descarga según `manifest.json` del release (trozos + zips), reanudable.
- `HikariCompat`: elige el modo gráfico según el móvil. Nivel 0 = Vulkan + DXVK (Turnip en Adreno, Vortek en el resto); nivel 1 = VirGL + WineD3D. Si el juego no llega a verse dos veces seguidas, se ofrece bajar al nivel 1.
- `HikariOptionInfoFixer`: ajusta `savedata/OptionInfo.lua` a la resolución del contenedor antes de cada arranque.
- `HikariLoadingView`: pantalla de carga.
- `HardcodedPathPatcher`: corrige las rutas `/data/data/com.winlator/...` grabadas en las librerías del rootfs.

### Ayudantes de Windows (`hikari_tools/`, compilados con mingw)

- `hikari_openurl.c` → `hikari_openurl.exe` (x86_64): sustituye a Internet Explorer para que los enlaces de las noticias del patcher se abran en el navegador de Android.
  Se compila con `x86_64-w64-mingw32-gcc -O2 -s -mwindows` enlazando ole32, oleaut32, uuid, urlmon y shell32.
- `hikari_setupok.c` → `hikari_setupok.exe` (i686): acepta automáticamente el diálogo "Ragnarok Setup" que el cliente abre la primera vez.
  `i686-w64-mingw32-gcc -O2 -s -mwindows hikari_setupok.c -o hikari_setupok.exe`

Tras compilarlos, copia el `.exe` a `app/src/main/assets/hikari/`.

## Cliente del juego (descarga)

El cliente **no** está en el código: está en el release [`v2-client`](https://github.com/Error471/HikariRO-Mobile/releases/tag/v2-client) de este repositorio.

- `manifest.json` lista los ficheros: `splitFiles` (ficheros grandes partidos en trozos que se unen, con tamaño y CRC32) y `zips` (se extraen sobre la carpeta del cliente).
- Ahora mismo: `data.grf.part1..3` y `hikariro_client_01..03.zip`.
- Para cambiar de release: `scripts/cambiar_repo_descarga.ps1` (escribe `hikari.properties`) y recompilar.
- Para subir un cliente nuevo: prepara los ficheros en `release_parts/v2/` y ejecuta `scripts/subir_release.ps1` (necesita GitHub CLI).

## Diagnóstico

- `HikariConfig.DIAGNOSTIC_MODE = true` vuelca la salida de Wine y Box64 a Logcat (etiqueta `HikariRO`). Ralentiza mucho el juego: dejarlo en `false` para publicar.
- `scripts/capturar_log.ps1`, `capturar_cierre.ps1` y `capturar_lentitud.ps1` recogen información por adb en `logs/`.

## Pendiente / conocido

- Solo se ha probado en un móvil (Nothing Phone, Adreno 730, Android 15). Otros móviles, sobre todo sin Adreno, están sin probar.
- El límite de FPS (`MAX_FPS`) está desactivado porque el limitador de DXVK dejaba el juego muy lento.
- nProtect GameGuard no ha dado problemas hasta ahora, pero es un riesgo con futuras versiones del cliente.
