# Ejecutar MIENTRAS el juego va lento (por ejemplo en la pantalla de login)
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = "com.hikarimovil.launcher"
$d   = "/data/data/$pkg"
$out = Join-Path $PSScriptRoot "..\logs\lentitud.txt"
$null = New-Item -ItemType Directory -Force (Split-Path $out)

Write-Host "Script de diagnostico iniciado"
if (-not (Test-Path $adb)) { Write-Host "No encuentro adb en $adb" -ForegroundColor Red; Read-Host "Enter para cerrar"; exit 1 }
Write-Host "Dispositivos:"
& $adb devices
"" | Out-File $out -Encoding utf8

function Paso($titulo, [scriptblock]$bloque) {
    Write-Host "-> $titulo"
    "== $titulo" | Out-File $out -Append -Encoding utf8
    & $bloque 2>&1 | Out-File $out -Append -Encoding utf8
}

Paso "preferencias (modo grafico)" { & $adb shell "run-as $pkg cat $d/shared_prefs/${pkg}_preferences.xml" }
Paso "contenedor" { & $adb shell "run-as $pkg ls $d/files/rootfs/home/" ; & $adb shell "run-as $pkg cat $d/files/rootfs/home/xuser-1/.container" }
Paso "dxvk.conf" { & $adb shell "run-as $pkg cat $d/files/rootfs/home/xuser/.config/dxvk.conf" }
Paso "procesos" { & $adb shell "ps -A -o PID,RSS,NAME | grep -iE 'hikari|wine|explorer|rag|box64'" }
foreach ($i in 1..3) {
    Paso "hilos que mas CPU usan (muestra $i)" { & $adb shell "top -H -b -n 1 -m 30" }
    Start-Sleep -Seconds 2
}
Paso "temperatura" { & $adb shell "dumpsys thermalservice | grep -iE 'status|temperature' | head -15" }
Paso "log HikariRO" { & $adb logcat -d -v threadtime -s HikariRO }
Paso "errores recientes" { & $adb logcat -d -t 400 "*:W" }

Write-Host "Listo: $out" -ForegroundColor Green
Read-Host "Pulsa Enter para cerrar"
