# Sube la APK compilada a GitHub para poder descargarla desde cualquier sitio (tambien desde el movil).
# Enlace fijo: https://github.com/<repo>/releases/download/app/HikariRO.apk
# Necesita GitHub CLI (gh). Antes: compila en Android Studio (Build -> Assemble Project).
param(
    [string]$Repo = "Error471/HikariRO-Mobile",
    [string]$Tag  = "app"
)
$apk = Join-Path $PSScriptRoot "..\app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path -LiteralPath $apk)) { Write-Host "No encuentro la APK en $apk. Compila primero en Android Studio." -ForegroundColor Red; Read-Host "Enter para cerrar"; exit 1 }
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Write-Host "Falta GitHub CLI (https://cli.github.com)." -ForegroundColor Red; Read-Host "Enter para cerrar"; exit 1 }
$null = & gh auth status 2>&1
if ($LASTEXITCODE -ne 0) { & gh auth login --web }

$info = Get-Item -LiteralPath $apk
Write-Host ("APK: {0:N0} MB, compilada el {1}" -f ($info.Length / 1MB), $info.LastWriteTime) -ForegroundColor Cyan

# Copia con nombre fijo para que el enlace no cambie
$tmp = Join-Path $PSScriptRoot "..\build"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$named = Join-Path $tmp "HikariRO.apk"
Copy-Item -LiteralPath $apk -Destination $named -Force

$notes = "APK de HikariRO para Android. Descargala en el movil e instalala (hay que permitir instalar apps de origen desconocido). Si tenias una version firmada por otro PC, desinstala la anterior primero."
$null = & gh release view $Tag --repo $Repo 2>&1
if ($LASTEXITCODE -eq 0) {
    & gh release upload $Tag $named --repo $Repo --clobber
} else {
    & gh release create $Tag $named --repo $Repo --title "HikariRO para Android (APK)" --notes $notes
}
if ($LASTEXITCODE -ne 0) { Write-Host "La subida ha fallado. Vuelve a ejecutar el script." -ForegroundColor Red; Read-Host "Enter para cerrar"; exit 1 }
Remove-Item -LiteralPath $named -Force
$url = "https://github.com/$Repo/releases/download/$Tag/HikariRO.apk"
Write-Host ""
Write-Host "Listo. Enlace de descarga:" -ForegroundColor Green
Write-Host "  $url"
Set-Clipboard -Value $url
Write-Host "(copiado al portapapeles)"
Read-Host "Enter para cerrar"
