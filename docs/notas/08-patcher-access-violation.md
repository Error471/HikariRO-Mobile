# Error "Access violation" del patcher al aplicar parches (19-11-2026)

- Síntoma: en algunos móviles, al descargar o aplicar un parche (p. ej. `20261025_palette_fix.thor`), el patcher Thor muestra "Access violation at address 00403FC9 in module 'HikariRO.exe'. Write of address 000204B0.". En otros móviles no pasa nunca.
- Análisis: 0x403FC9 está en FreeMem del gestor de memoria de Delphi (FastMM, `lock cmpxchg byte [ebx], ah`); la cabecera del bloque liberado está corrupta, es decir, el heap se ha corrompido.
- Causa probable: el patcher es multihilo (descarga y parcheo a la vez) y el preset Box64 INTERMEDIATE usa `BOX64_DYNAREC_STRONGMEM=0`, `WEAKBARRIER=2` y `BIGBLOCK=2`. ARM no respeta el orden de memoria de x86 si no se emula, y los cerrojos de FastMM acaban corrompiendo el heap. Depende del procesador de cada móvil.
- Arreglo: `app/src/main/assets/box64/default.box64rc` (se copia a `/etc/config.box64rc` en cada arranque, `BOX64_RCFILE`) tiene secciones `[HikariRO.exe]` y `[tmp.exe]` con `STRONGMEM=2`, `WEAKBARRIER=0`, `BIGBLOCK=0`, `CALLRET=0`, `FASTNAN=0`. Box64 0.4.4 aplica estas secciones por nombre de proceso (Wine lo fija con prctl) y tienen prioridad sobre las variables de entorno. El juego (`raghikari.exe`) sigue con el preset rápido.
- Versión 1.1 de la app (`versionCode 2`).
- Si sigue fallando en algún móvil: probar `BOX64_DYNAREC=0` solo en `[HikariRO.exe]` (el patcher iría lento, pero sin dynarec).
