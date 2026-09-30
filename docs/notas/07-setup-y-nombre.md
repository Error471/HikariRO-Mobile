# Diálogo "Ragnarok Setup" y nombre de la app (30-09-2026)

## Setup.exe la primera vez
- El `savedata/OptionInfo.lua` del cliente limpio no tiene dispositivo gráfico (`DX9DEVICEID` a ceros, `DEVICECNT = 0`), así que `raghikari.exe` abre `Setup.exe` para elegirlo. Al aceptar, Setup guarda el dispositivo en OptionInfo.lua y no vuelve a salir (salvo si cambia el dispositivo, por ejemplo al cambiar de modo gráfico).
- `hikari_tools/hikari_setupok.c` → `assets/hikari/hikari_setupok.exe` (i686): busca diálogos `#32770` del proceso `setup.exe`, los saca de la pantalla y les manda `WM_COMMAND IDOK` (igual que pulsar 확인/Aceptar). Termina cuando Setup se cierra, o a los 60 s.
- `XServerDisplayActivity`:
  - `installOpenUrlHelper()` copia también el exe a `drive_c/windows/`.
  - `setupLoadingOverlay`: al mapearse una ventana de clase `setup.exe*` o con título "Ragnarok Setup", lanza el ayudante con `winHandler.exec` y muestra la pantalla de carga ("Preparando la configuración del juego...").
- Sin probar todavía en el móvil.

## Nombre
- `app_name` = "HikariRO" en `values`, `values-pt` y `values-ru` (en pt/ru seguía "Winlator"); también los textos de la notificación en pt/ru.
