# Captura de diagnostico HikariRO Movil (movil conectado por USB)
$adb  = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg  = "com.hikarimovil.launcher"
$out  = Join-Path $PSScriptRoot "..\logs"
$null = New-Item -ItemType Directory -Force $out

# Arranque limpio de la app
& $adb logcat -G 16M
& $adb logcat -b all -c
& $adb shell am force-stop $pkg
& $adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
Write-Host "App lanzada. Cuando aparezca el patcher, pulsa una noticia y espera ~20 s. Luego pulsa Enter aqui."
Read-Host | Out-Null

# Log completo
& $adb logcat -b all -d -v threadtime > "$out\log2.txt"

# Comprobaciones dentro del movil
$d = "/data/data/$pkg"
& {
  "== version rootfs";          & $adb shell run-as $pkg cat "$d/files/rootfs/.winlator/.rfs_version"
  "== libfontconfig (tamano)";  & $adb shell run-as $pkg ls -l "$d/files/rootfs/usr/lib/libfontconfig.so.1.14.0"
  "== libxcb (0 = parcheado)";  & $adb shell run-as $pkg grep -c com.winlator "$d/files/rootfs/usr/lib/libxcb.so.1.1.0"
  "== socket real";             & $adb shell run-as $pkg ls -la "$d/files/rootfs/tmp/.X11-unix/"
  "== enlace corto";            & $adb shell run-as $pkg ls -la "$d/tmp/.X11-unix/"
  "== OptionInfo";              & $adb shell run-as $pkg grep -E "WIDTH|HEIGHT|XPos|YPos" "$d/files/HikariRO/savedata/OptionInfo.lua"
  "== registro de hikari_openurl.exe";   & $adb shell run-as $pkg cat "$d/files/rootfs/home/xuser/.wine/drive_c/windows/temp/hikari_openurl.log"
  "== registro COM (LocalServer32)";     & $adb shell run-as $pkg grep -A2 "0002DF01-0000-0000-C000-000000000046}\\\\LocalServer32" "$d/files/rootfs/home/xuser/.wine/system.reg"
  "== procesos";                & $adb shell "ps -A | grep -ciE 'explorer'"
} *> "$out\check.txt"
Write-Host "Listo: log2.txt y check.txt en $out"
