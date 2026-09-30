# Cambia la URL desde la que HikariRO Movil descarga el cliente, sin tocar el codigo.
# Uso:
#   powershell -ExecutionPolicy Bypass -File cambiar_repo_descarga.ps1
#   powershell -ExecutionPolicy Bypass -File cambiar_repo_descarga.ps1 -Url "usuario/repo@etiqueta"
# Formatos aceptados:
#   https://github.com/usuario/repo/releases/download/etiqueta/
#   https://github.com/usuario/repo/releases/tag/etiqueta
#   usuario/repo@etiqueta
#   cualquier otra URL https que contenga los ficheros del cliente
# Despues hay que volver a compilar la app.

param(
    [string]$Url = "",
    [switch]$SinComprobar
)

$ErrorActionPreference = "Stop"
$propsFile = Join-Path $PSScriptRoot "..\hikari.properties"
$testFile = "manifest.json"

function Get-CurrentUrl {
    if (-not (Test-Path $propsFile)) { return $null }
    foreach ($line in Get-Content $propsFile) {
        if ($line -match '^\s*download_base_url\s*=\s*(.+)$') { return $Matches[1].Trim() }
    }
    return $null
}

function Convert-ToBaseUrl([string]$value) {
    $value = $value.Trim().Trim('"')
    if ($value -match '^([\w.-]+)/([\w.-]+)@(.+)$') {
        return "https://github.com/$($Matches[1])/$($Matches[2])/releases/download/$($Matches[3])/"
    }
    if ($value -match '^https://github\.com/([\w.-]+)/([\w.-]+)/releases/tag/([^/?#]+)') {
        return "https://github.com/$($Matches[1])/$($Matches[2])/releases/download/$($Matches[3])/"
    }
    if ($value -notmatch '^https?://') { throw "La URL debe empezar por https:// (o usar el formato usuario/repo@etiqueta)." }
    if (-not $value.EndsWith("/")) { $value += "/" }
    return $value
}

Write-Host ""
Write-Host "=== HikariRO Movil: repositorio de descarga del cliente ===" -ForegroundColor Cyan
$current = Get-CurrentUrl
if ($current) { Write-Host "URL actual: $current" } else { Write-Host "No se ha encontrado $propsFile (se creara)." }
Write-Host ""

if (-not $Url) {
    $Url = Read-Host "Nueva URL (o usuario/repo@etiqueta). Enter para cancelar"
    if (-not $Url) { Write-Host "Cancelado, no se ha cambiado nada."; exit 0 }
}

try { $newUrl = Convert-ToBaseUrl $Url } catch { Write-Host $_.Exception.Message -ForegroundColor Red; exit 1 }
Write-Host "Nueva URL: $newUrl"

if (-not $SinComprobar) {
    Write-Host "Comprobando que existe $testFile en esa ruta..."
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $response = Invoke-WebRequest -Uri ($newUrl + $testFile) -Method Head -UseBasicParsing -MaximumRedirection 5
        Write-Host "OK: el fichero responde (codigo $($response.StatusCode))." -ForegroundColor Green
    } catch {
        Write-Host "Aviso: no se ha podido acceder a $($newUrl + $testFile)" -ForegroundColor Yellow
        Write-Host "       $($_.Exception.Message)" -ForegroundColor Yellow
        $answer = Read-Host "Guardar la URL igualmente? (s/n)"
        if ($answer -notmatch '^[sS]') { Write-Host "Cancelado, no se ha cambiado nada."; exit 1 }
    }
}

$lines = @(
    "# URL base desde la que la app descarga el cliente de HikariRO.",
    "# Cambiala con cambiar_repo_descarga.ps1 y vuelve a compilar la app.",
    "download_base_url=$newUrl"
)
[IO.File]::WriteAllText($propsFile, ($lines -join "`r`n") + "`r`n", [Text.Encoding]::ASCII)

Write-Host ""
Write-Host "Guardado en $propsFile" -ForegroundColor Green
Write-Host "Vuelve a compilar la app en Android Studio para que use la nueva URL."
Write-Host "Nota: el repositorio nuevo debe tener manifest.json (como el de release_parts\v2) con la lista de ficheros del cliente."
