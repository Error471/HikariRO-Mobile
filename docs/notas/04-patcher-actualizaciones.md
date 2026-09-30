# HikariRO Móvil — Patcher y actualizaciones (29-09-2026)

Para recibir actualizaciones se ejecuta primero `HikariRO.exe` (el patcher); al pulsar "Jugar", el patcher lanza `raghikari.exe`.

## Qué es HikariRO.exe
- **Thor Patcher**, Delphi, 32 bits. Configuración en el recurso RCDATA `CONFIG`:
  - `RootURL='http://patch.hikariro.com/'`, `RemoteConfigFile='main.ini'`, `ClientEXE='raghikari.exe'`, `ClientParameter='-1sak1'`, `FinishOnConnectionFailure=true`, `DefaultGRF='HikariRO.grf'`.
  - Noticias: dos `NoticeBox` (control WebBrowser/IE).
  - Ventana sin bordes de 904x554 (cabe en 1336x600).
- Puede autoactualizarse (`update_patcher` → `tmp.exe`) y pedir elevación (`runas`).

## Lanzamiento
- `HikariConfig.PATCHER_EXE_NAME = "HikariRO.exe"`, `USE_PATCHER = true` y `getLaunchExe(clientDir)`: lanza el patcher si existe; si no, `raghikari.exe` directo.
- `XServerDisplayActivity.setupLoadingOverlay`: carga → se quita cuando el patcher (clase `hikariro*`/`tmp.exe`, ≥300 px) dibuja → se quita del todo cuando raghikari (≥800 px) dibuja.
- Al desaparecer la ventana del patcher → `checkAfterPatcherClosed`: pregunta a Wine la lista de procesos hasta 9 veces cada 0,5 s. Si aparece `raghikari*` → "Iniciando el juego..." (tope 45 s); si no y el patcher tampoco sigue → la app se cierra.
- Controles ocultos mientras se ve el patcher (`hikariGameVisible`).

## Problema 1: "Failed to communicate with server" → DNS roto
- glibc del rootfs (`libc.so.6`, `libresolv.so.2`) tiene grabadas las rutas del paquete original (`/data/data/com.winlator/files/rootfs/etc/...`).
- Arreglo (rootfs v30): `RootFSInstaller` parchea el prefijo `.../rootfs/etc` → `/data/data/<paquete>/etc`. `NetworkInfoUpdateComponent.updateResolvConfFile` escribe resolv.conf con los DNS de la red + 1.1.1.1 y 8.8.8.8.

## Problema 2: noticias en blanco → falta Wine Gecko
- `assets/hikari/wine-gecko-2.47.4-x86.msi`. `ensureWineGecko()` lo copia a `rootfs/opt/wine/share/wine/gecko/`; Wine lo instala solo.

## Problema 3: "Access violation ... in module 'iphlpapi.dll'"
- `nsiproxy.so` lee `/data/data/<paquete>/tmp/ifaddrs`. `NetworkInfoUpdateComponent.updateIFAddrsFile` escribe también ahí.

## Problema 4: al pulsar una noticia se quedaba bloqueado → navegador del móvil
- Wine (`dlls/mshtml/navigate.c`, `navigate_new_window`) pide por COM un Internet Explorer fuera de proceso (`CLSID_InternetExplorer {0002DF01-0000-0000-C000-000000000046}`), lentísimo bajo Box64 y oculto detrás del patcher.
- `hikari_tools/hikari_openurl.c` (x86_64): servidor COM que se registra como ese CLSID, implementa lo mínimo de `IWebBrowser2` e `ITargetFramePriv2`, saca la URL y la abre con `ShellExecuteW` → `winebrowser.exe` → `winhandler.exe /url` → navegador de Android. Log en `C:\windows\temp\hikari_openurl.log`.
- `XServerDisplayActivity.installOpenUrlHelper()` copia el exe y pone `LocalServer32` en `system.reg`.

## Problema 5: tras lo anterior el patcher se congelaba
- COM no podía registrar el servidor: `Failed to start RpcSs service` (servicio deshabilitado por `WineUtils.changeServicesStatus` de Winlator).
- Arreglo: `WineUtils.enableRpcSsOnDemand(container)` pone `RpcSs\Start=3` en cada arranque.

## Si algo falla
- `DIAGNOSTIC_MODE = true` (WINEDEBUG `+err,+ole,+seh,+process,+loaddll`) y `scripts/capturar_log.ps1`.
- Para no usar el patcher: `USE_PATCHER = false`.
