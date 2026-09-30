# HikariRO Móvil — Estética: pantalla de carga, controles e icono (29-09-2026)

Estilo: moderno oscuro, fondo con arte del propio cliente, botones con iconos, discretos y con ocultar/mostrar.

## Pantalla de carga — `hikariro/HikariLoadingView.java`
- Se usa en el lanzador (`HikariLauncherActivity`) y encima del juego (`XServerDisplayActivity.setupLoadingOverlay`). Misma ilustración en ambas y transición sin animación entre las dos actividades.
- En el contenedor HikariRO no se muestra el diálogo "Starting up..." de Winlator.
- Diseño: fondo = ilustración desenfocada y oscurecida; ilustración nítida 4:3 a la derecha con zoom lento; degradado oscuro; columna izquierda con "RAGNAROK ONLINE", "HikariRO", estado (`SpinnerView` + texto), barra de progreso con acento #9BD7FF y un consejo aleatorio.
- Estados: "Preparando el sistema..." → "Arrancando HikariRO..." (primera ventana X) → "Cargando HikariRO..." (ventana de raghikari) → se desvanece cuando raghikari (≥800 px) dibuja. Tope 3 min.
- Arte: 12 ilustraciones de carga extraídas de `data.grf` (`loadingNN.jpg`, 640x480). En `assets/hikari/loading/NN.jpg` + `NN_blur.jpg`.

## Controles
- `InputControlsView.setHikariStyle(true)` → `ControlElement.drawHikariButton`: fondo negro 30%, borde blanco 22%, acento celeste al pulsar o con interruptor activo; iconos teñidos.
- Iconos propios (PNG blancos 64 px en `assets/inputcontrols/icons/`): 40 menú (Esc), 41 chat (Enter), 42 descansar (Insert), 43 clic derecho.
- Botones flotantes arriba a la derecha (`setupFloatingButtons`): ajustes, ojo (ocultar/mostrar controles, preferencia `hikari_controls_hidden`) y teclado.

## Nombres de ventana
- Wine envía WM_NAME como `COMPOUND_TEXT`; `xserver/Property.toString()` lo decodifica (antes solo `STRING`/`UTF8_STRING`).
- `watchGravityErrorHandler`: título con "error handler" → cierra a los 0,5 s; plan B: si la ventana principal de raghikari se desmapea y no vuelve en 3 s → cierra la app.

## Icono de la app
- Fuente: `data\texture\scr_logo.bmp` de `hdata.grf` (150x100, fondo magenta como transparencia), recortado y con alfa suave.
- Icono adaptativo (`mipmap-anydpi-v26/ic_launcher*.xml`): fondo `drawable-nodpi/ic_launcher_bg_hikari.png` + primer plano `mipmap-*/ic_launcher_foreground.png`. Iconos clásicos en las 5 densidades.
- El logo original es pequeño: se ve algo suave en pantallas de muy alta densidad. Se puede sustituir por uno en alta resolución.

## Notificación del servicio en primer plano
- `services/ForegroundService` (tipo mediaPlayback). Título "HikariRO", textos en `fgs_notification_*`.
- Icono pequeño `drawable-*/hikari_ic_notification.png` ("H" con destello), icono grande `mipmap/ic_launcher_round`, acento #9BD7FF.
- Ver nota 05: la notificación solo aparece mientras la app está en segundo plano.
