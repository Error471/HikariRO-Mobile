# Captura el motivo de un cierre de HikariRO Movil (movil conectado por USB)
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = "com.hikarimovil.launcher"
$out = Join-Path $PSScriptRoot "..\logs"
$null = New-Item -ItemType Directory -Force $out

Write-Host "Dispositivos conectados:"
& $adb devices

& $adb logcat -b all -c
& $adb shell am force-stop $pkg
& $adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1
Write-Host "App lanzada. Esperando 15 segundos..."
Start-Sleep -Seconds 15

& $adb logcat -b all -d -v threadtime > "$out\cierre.txt"
Write-Host "Listo: $out\cierre.txt"
Read-Host "Pulsa Enter para cerrar"
