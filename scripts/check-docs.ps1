# Comprueba que la documentacion siga coincidiendo con el codigo.
#   pwsh -File scripts/check-docs.ps1
# Falla (exit 1) si un recurso existe en el codigo y no esta documentado, o si
# un conteo del mapa de recursos dejo de ser cierto. No genera nada: solo mira.
#
# Nota: este archivo es SOLO ASCII a proposito. Windows PowerShell 5.1 lee los
# .ps1 como ANSI, y un caracter no ASCII (una raya, una tilde) puede romper el
# analizador. Los documentos se normalizan quitando tildes antes de comparar.
$ErrorActionPreference = 'Stop'

$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } else { (Get-Location).Path }

function Leer([string]$rel) {
  $p = Join-Path $root $rel
  if (-not (Test-Path $p)) { throw "No existe: $rel" }
  return [System.IO.File]::ReadAllText($p)
}

function SinTildes([string]$t) {
  # rayas tipograficas y comillas "inteligentes" -> equivalentes ASCII
  $t = $t.Replace([char]0x2014, '-')
  $t = $t.Replace([char]0x2013, '-')
  $t = $t.Replace([char]0x2018, "'")
  $t = $t.Replace([char]0x2019, "'")
  $t = $t.Replace([char]0x201C, '"')
  $t = $t.Replace([char]0x201D, '"')
  $form = $t.Normalize([Text.NormalizationForm]::FormD)
  $sb = New-Object Text.StringBuilder
  foreach ($ch in $form.ToCharArray()) {
    $cat = [Globalization.CharUnicodeInfo]::GetUnicodeCategory($ch)
    if ($cat -ne [Globalization.UnicodeCategory]::NonSpacingMark) { [void]$sb.Append($ch) }
  }
  return $sb.ToString().Normalize([Text.NormalizationForm]::FormC)
}

function Nombres([string]$texto, [string]$patron) {
  $out = New-Object System.Collections.Generic.List[string]
  foreach ($m in [regex]::Matches($texto, $patron)) {
    $v = $m.Groups[1].Value
    if (-not $out.Contains($v)) { $out.Add($v) }
  }
  return $out
}

$mapa = SinTildes (Leer 'docs/MAPA-RECURSOS.md')
$api = SinTildes (Leer 'docs/API-BACKEND.md')
$sql = Leer 'web/supabase-setup.sql'
$manifest = Leer 'android/app/src/main/AndroidManifest.xml'
$settings = Leer 'android/app/src/main/java/com/locator/agent/data/SettingsRepository.kt'
$command = Leer 'android/app/src/main/java/com/locator/agent/sync/RemoteCommand.kt'
$config = Leer 'web/config.js'
$strings = Leer 'android/app/src/main/res/values/strings.xml'

$fail = 0
function Bien([string]$que) { Write-Output ("OK    " + $que) }
function Mal([string]$que) { $script:fail++; Write-Output ("FALTA " + $que) }

# --- recursos del backend -----------------------------------------------------
$tablas = Nombres $sql 'create table if not exists public\.([a-z_]+)'
$vistas = Nombres $sql 'create or replace view public\.([a-z_]+)'
$funciones = Nombres $sql 'create or replace function public\.([a-z_]+)'
$politicas = Nombres $sql 'create policy ([a-z_]+)'
$indices = Nombres $sql 'create index if not exists ([a-z_]+)'
$tiposEvento = 0
foreach ($m in [regex]::Matches($sql, 'check \(kind in \(([^)]*)\)')) {
  $tiposEvento += ([regex]::Matches($m.Groups[1].Value, "'[a-z]+'")).Count
}

foreach ($t in $tablas) { if ($mapa.Contains($t)) { Bien "tabla $t" } else { Mal "tabla $t" } }
foreach ($v in $vistas) { if ($mapa.Contains($v)) { Bien "vista $v" } else { Mal "vista $v" } }
foreach ($f in $funciones) {
  if (($mapa + $api).Contains($f)) { Bien "funcion $f" } else { Mal "funcion $f" }
}
foreach ($p in $politicas) { if ($mapa.Contains($p)) { Bien "politica $p" } else { Mal "politica $p" } }

# --- comandos remotos ---------------------------------------------------------
$bloque = [regex]::Match($command, 'val ALLOWED = setOf\(([\s\S]*?)\n\s*\)').Groups[1].Value
$comandos = Nombres $bloque '"([a-z_]+)"'
foreach ($c in $comandos) {
  if ($mapa.Contains($c) -and $api.Contains($c)) { Bien "comando $c" }
  else { Mal "comando $c (en la lista blanca y no documentado)" }
}

# --- ajustes -----------------------------------------------------------------
$clavesAgente = Nombres $settings 'const val KEY_[A-Z_]+ = "([a-z_]+)"'
foreach ($k in $clavesAgente) { if ($mapa.Contains($k)) { Bien "ajuste $k" } else { Mal "ajuste $k" } }

$clavesPanel = Nombres $config '(?m)^\s{2}([a-zA-Z_]+):'
foreach ($k in $clavesPanel) { if ($mapa.Contains($k)) { Bien "config $k" } else { Mal "config $k" } }

# --- archivos -----------------------------------------------------------------
$kt = Get-ChildItem -Path (Join-Path $root 'android/app/src/main/java') -Recurse -Filter *.kt
foreach ($f in $kt) { if ($mapa.Contains($f.Name)) { Bien "kotlin $($f.Name)" } else { Mal "kotlin $($f.Name)" } }

$web = Get-ChildItem -Path (Join-Path $root 'web') -File
foreach ($f in $web) { if ($mapa.Contains($f.Name)) { Bien "web $($f.Name)" } else { Mal "web $($f.Name)" } }

$resDir = Join-Path $root 'android/app/src/main/res'
$res = Get-ChildItem -Path $resDir -Recurse -File
foreach ($f in $res) {
  $rel = $f.FullName.Substring($resDir.Length + 1).Replace('\', '/')
  if ($mapa.Contains($rel)) { Bien "recurso $rel" } else { Mal "recurso $rel" }
}

$flows = Get-ChildItem -Path (Join-Path $root '.github/workflows') -File
foreach ($f in $flows) { if ($mapa.Contains($f.Name)) { Bien "workflow $($f.Name)" } else { Mal "workflow $($f.Name)" } }

$tools = Get-ChildItem -Path (Join-Path $root 'tools') -File
foreach ($f in $tools) { if ($mapa.Contains($f.Name)) { Bien "herramienta $($f.Name)" } else { Mal "herramienta $($f.Name)" } }

$build = Get-ChildItem -Path (Join-Path $root 'android') -Recurse -File -Include '*.gradle.kts', '*.properties', '*.pro'
foreach ($f in $build) { if ($mapa.Contains($f.Name)) { Bien "build $($f.Name)" } else { Mal "build $($f.Name)" } }

# --- conteos declarados en el mapa --------------------------------------------
$permisos = ([regex]::Matches($manifest, '<uses-permission')).Count
$textos = ([regex]::Matches($strings, 'name="')).Count

$esperado = @(
  @{ Que = 'conteo de archivos Kotlin'; Texto = "($($kt.Count) archivos Kotlin)" },
  @{ Que = 'conteo de archivos de compilacion'; Texto = "### 2.4 Compilacion ($($build.Count) archivos)" },
  @{ Que = 'conteo de recursos Android'; Texto = "### 2.2 Recursos Android ($($res.Count))" },
  @{ Que = 'conteo de permisos'; Texto = "### 2.3 Permisos ($permisos)" },
  @{ Que = 'conteo de tablas'; Texto = "### 3.1 Tablas ($($tablas.Count))" },
  @{ Que = 'conteo de vistas'; Texto = "### 3.2 Vistas ($($vistas.Count))" },
  @{ Que = 'conteo de funciones'; Texto = "### 3.3 Funciones ($($funciones.Count))" },
  @{ Que = 'conteo de politicas'; Texto = "**$($politicas.Count) politicas** nombradas" },
  @{ Que = 'conteo de indices'; Texto = "**$($indices.Count) indices**" },
  @{ Que = 'conteo de archivos web'; Texto = "## 4. Inventario: panel web ($($web.Count) archivos)" },
  @{ Que = 'conteo de comandos'; Texto = "## 5. Catalogo de comandos remotos ($($comandos.Count))" },
  @{ Que = 'conteo de tipos de evento'; Texto = "## 6. Catalogo de eventos ($tiposEvento tipos)" },
  @{ Que = 'conteo de claves del agente'; Texto = "### 7.1 Agente ($($clavesAgente.Count) claves cifradas" },
  @{ Que = 'conteo de claves del panel'; Texto = "### 7.2 Panel (``web/config.js``) - $($clavesPanel.Count) claves" },
  @{ Que = 'conteo de textos Android'; Texto = "$textos textos y nombres de canal" }
)
foreach ($e in $esperado) {
  if ($mapa.Contains($e.Texto)) { Bien $e.Que } else { Mal "$($e.Que): el mapa deberia decir '$($e.Texto)'" }
}

# --- enlaces entre documentos -------------------------------------------------
$docs = @()
$docs += Get-ChildItem -Path (Join-Path $root 'docs') -File -Filter *.md
foreach ($extra in 'README.md', 'SETUP.md') {
  $p = Join-Path $root $extra
  if (Test-Path $p) { $docs += Get-Item $p }
}
foreach ($d in $docs) {
  foreach ($m in [regex]::Matches([System.IO.File]::ReadAllText($d.FullName), '\]\(([^)#]+\.md)\)')) {
    $target = Join-Path $d.DirectoryName $m.Groups[1].Value
    if (Test-Path $target) { Bien "enlace $($d.Name) -> $($m.Groups[1].Value)" }
    else { Mal "enlace roto en $($d.Name): $($m.Groups[1].Value)" }
  }
}

Write-Output ("Resumen: tablas=$($tablas.Count) vistas=$($vistas.Count) funciones=$($funciones.Count) " +
    "politicas=$($politicas.Count) indices=$($indices.Count) comandos=$($comandos.Count) " +
    "ajustes=$($clavesAgente.Count)+$($clavesPanel.Count) kotlin=$($kt.Count) fallos=$fail")

if ($fail -gt 0) { exit 1 }
