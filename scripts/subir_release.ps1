# Sube el cliente limpio (release_parts\v2) a un release de GitHub y hace que la app lo use.
# Necesita GitHub CLI (https://cli.github.com). La primera vez pedira iniciar sesion en GitHub.
param(
    [string]$Repo = "Error471/hikariro-mobile",
    [string]$Tag  = "v2-client"
)
$dir = Join-Path $PSScriptRoot "..\release_parts\v2"
$files = @("manifest.json", "data.grf.part1", "data.grf.part2", "data.grf.part3",
           "hikariro_client_01.zip", "hikariro_client_02.zip", "hikariro_client_03.zip") | ForEach-Object { Join-Path $dir $_ }

foreach ($f in $files) { if (-not (Test-Path $f)) { Write-Host "Falta $f" -ForegroundColor Red; Read-Host "Enter para cerrar"; exit 1 } }

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Write-Host "No tienes GitHub CLI (gh). Instalalo desde https://cli.github.com y vuelve a ejecutar este script." -ForegroundColor Yellow
    Write-Host "Alternativa: crea a mano en GitHub un release '$Tag' en $Repo y sube estos ficheros:"
    $files | ForEach-Object { Write-Host "  $_" }
    Read-Host "Enter para cerrar"; exit 1
}

$null = & gh auth status 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "Inicia sesion en GitHub (se abrira el navegador)..."
    & gh auth login --web
    if ($LASTEXITCODE -ne 0) { Write-Host "No se pudo iniciar sesion." -ForegroundColor Red; Read-Host "Enter para cerrar"; exit 1 }
}

Write-Host "Subiendo el cliente a $Repo (release $Tag). Son unos 5 GB, puede tardar bastante..." -ForegroundColor Cyan
$null = & gh release view $Tag --repo $Repo 2>&1
if ($LASTEXITCODE -eq 0) {
    & gh release upload $Tag @files --repo $Repo --clobber
} else {
    & gh release create $Tag @files --repo $Repo --title "Cliente HikariRO limpio (v2)" --notes "Cliente limpio para HikariRO Movil. La app lee manifest.json para saber que ficheros descargar."
}
if ($LASTEXITCODE -ne 0) { Write-Host "La subida ha fallado. Puedes volver a ejecutar el script: los ficheros ya subidos se reemplazan." -ForegroundColor Red; Read-Host "Enter para cerrar"; exit 1 }

Write-Host "Subida completa. Apuntando la app al release nuevo..." -ForegroundColor Green
& (Join-Path $PSScriptRoot "cambiar_repo_descarga.ps1") -Url "$Repo@$Tag"
Write-Host "Listo. Recompila la app para que descargue el cliente nuevo." -ForegroundColor Green
Read-Host "Enter para cerrar"
