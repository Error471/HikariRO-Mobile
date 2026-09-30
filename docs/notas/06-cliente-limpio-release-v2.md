# HikariRO Móvil — Cliente limpio en GitHub (release v2) (30-09-2026)

## Dónde está
- Release [`v2-client`](https://github.com/Error471/HikariRO-Mobile/releases/tag/v2-client) de `Error471/HikariRO-Mobile`:
  `data.grf.part1` (1,27 GB), `data.grf.part2` (1,27 GB), `data.grf.part3` (1,21 GB), `hikariro_client_01.zip` (449 MB), `_02.zip` (425 MB), `_03.zip` (189 MB) y `manifest.json`.
- `hikari.properties`, el respaldo de `app/build.gradle` y `HikariConfig.DEFAULT_DOWNLOAD_BASE_URL` apuntan a `https://github.com/Error471/hikariro-mobile/releases/download/v2-client/` (GitHub no distingue mayúsculas).
- El release antiguo `v1-client` (repo `hikariro-descarga`) ya no se usa.

## El cliente limpio
- 6161 entradas, unos 5,0 GB descomprimidos.
- `data.grf` (4 029 686 092 bytes) partido en tres trozos (1 363 148 800 + 1 363 148 800 + 1 303 388 492 bytes); CRC32 del conjunto 4046115150.
- `hikariro_client_01..03.zip` con el resto de ficheros (rutas relativas a la carpeta del cliente).
- `manifest.json`: `splitFiles` (nombre, tamaño, crc32, partes) y `zips`.

## En la app
- `HikariClientDownloader`:
  - Descarga primero `manifest.json` y, si no existe, usa la lista integrada de `HikariConfig`. Cambiar los ficheros del release no obliga a tocar el código.
  - Reanudable; un trozo ya completo (HTTP 416) cuenta como terminado.
  - Rechaza rutas fuera de la carpeta al extraer (zip-slip).
- `HikariConfig.MIN_FREE_SPACE_BYTES` = 11 GB (trozos + fichero final a la vez).
- `HikariLauncherActivity`: el cliente solo se da por instalado si existe raghikari.exe y no queda la carpeta `.hikari_download_tmp`.

## Subir un cliente nuevo
- Preparar los ficheros en `release_parts/v2/` (con su `manifest.json`) y ejecutar `scripts/subir_release.ps1` (GitHub CLI). Crea o actualiza el release y apunta la app a él; después hay que recompilar.
