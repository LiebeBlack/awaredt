# Comprueba el "cableado" entre piezas: que cada referencia exista de verdad.
#   pwsh -File scripts/check-wiring.ps1
#
#   - Kotlin: cada R.id.X y R.string.X existe, y el tipo del findViewById
#     coincide con la etiqueta XML (un Button leido como TextView es error).
#   - AndroidManifest: cada clase declarada existe como archivo .kt.
#   - Cada archivo .kt esta en la carpeta de su package (si no, no compila).
#   - Web: cada id que usa admin.js existe en admin.html, y cada id de app.js
#     existe en index.html; cada onclick="fn()" tiene su function en el JS.
#
# Solo ASCII a proposito (Windows PowerShell 5.1 lee los .ps1 como ANSI).
$ErrorActionPreference = 'Stop'

$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } else { (Get-Location).Path }
function Leer([string]$rel) { return [System.IO.File]::ReadAllText((Join-Path $root $rel)) }

$fail = 0
function Bien([string]$q) { Write-Output ("OK    " + $q) }
function Mal([string]$q) { $script:fail++; Write-Output ("FALTA " + $q) }

$javaDir = Join-Path $root 'android/app/src/main/java'
$resDir = Join-Path $root 'android/app/src/main/res'
$layoutDir = Join-Path $resDir 'layout'

# ---------------------------------------------------------------- XML de recursos
$xmlNames = @()          # todos los @+id/ y @string/ definidos
$idsPorTipo = @{}        # id -> etiqueta XML (Button, TextView, CheckBox...)
$stringsDef = @()

foreach ($f in Get-ChildItem -Path $resDir -Recurse -File -Filter *.xml) {
  $txt = [System.IO.File]::ReadAllText($f.FullName)
  foreach ($m in [regex]::Matches($txt, '@\+id/([A-Za-z_][A-Za-z0-9_]*)')) {
    $xmlNames += $m.Groups[1].Value
  }
  foreach ($m in [regex]::Matches($txt, '<(Button|TextView|EditText|CheckBox|Spinner|ImageView|Switch|LinearLayout|ScrollView|ImageView)\b[^>]*@\+id/([A-Za-z_][A-Za-z0-9_]*)', 'Singleline')) {
    $idsPorTipo[$m.Groups[2].Value] = $m.Groups[1].Value
  }
  if ($f.Name -eq 'strings.xml') {
    foreach ($m in [regex]::Matches($txt, '<string name="([A-Za-z_][A-Za-z0-9_]*)"')) {
      $stringsDef += $m.Groups[1].Value
    }
  }
}

# ---------------------------------------------------------------- Kotlin
$kt = Get-ChildItem -Path $javaDir -Recurse -Filter *.kt
$kotlinFuentes = @{}
foreach ($f in $kt) { $kotlinFuentes[$f.FullName] = [System.IO.File]::ReadAllText($f.FullName) }

foreach ($f in $kt) {
  $txt = $kotlinFuentes[$f.FullName]
  $rel = $f.FullName.Substring($javaDir.Length + 1) -replace '\\', '/'
  $esperado = ($rel -replace '/[^/]+\.kt$', '').Replace('/', '.')
  $m = [regex]::Match($txt, '(?m)^package\s+([A-Za-z0-9_.]+)')
  if (-not $m.Success) { Mal "$rel sin linea package" }
  elseif ($m.Groups[1].Value -ne $esperado) { Mal "$rel declara package $($m.Groups[1].Value) y deberia ser $esperado" }
  else { Bien "package $rel" }

  foreach ($r in [regex]::Matches($txt, 'R\.id\.([A-Za-z_][A-Za-z0-9_]*)')) {
    $id = $r.Groups[1].Value
    if ($xmlNames -notcontains $id) { Mal "$rel usa R.id.$id y no existe en ningun layout" }
  }
  foreach ($r in [regex]::Matches($txt, 'R\.string\.([A-Za-z_][A-Za-z0-9_]*)')) {
    $s = $r.Groups[1].Value
    if ($stringsDef -notcontains $s) { Mal "$rel usa R.string.$s y no esta en strings.xml" }
  }
  # findViewById<Tipo>(R.id.X) contra la etiqueta real del XML
  foreach ($r in [regex]::Matches($txt, 'findViewById(?:<([A-Za-z]+)>\s*)?\(\s*R\.id\.([A-Za-z_][A-Za-z0-9_]*)')) {
    $tipo = $r.Groups[1].Value
    $id = $r.Groups[2].Value
    if ($tipo -and $idsPorTipo.ContainsKey($id)) {
      $real = $idsPorTipo[$id]
      if ($tipo -ne $real) { Mal "$rel lee R.id.$id como $tipo pero en el XML es $real" }
    }
  }
}

# referencias a @string/ desde layouts y estilos
foreach ($f in Get-ChildItem -Path $resDir -Recurse -File -Filter *.xml) {
  $txt = [System.IO.File]::ReadAllText($f.FullName)
  foreach ($m in [regex]::Matches($txt, '@string/([A-Za-z_][A-Za-z0-9_]*)')) {
    $s = $m.Groups[1].Value
    if ($stringsDef -notcontains $s) { Mal "$($f.Name) usa @string/$s y no existe" }
  }
  foreach ($m in [regex]::Matches($txt, '@color/([A-Za-z_][A-Za-z0-9_]*)')) {
    $c = $m.Groups[1].Value
    $colores = [System.IO.File]::ReadAllText((Join-Path $resDir 'values/colors.xml'))
    if ($colores -notmatch ('name="' + [regex]::Escape($c) + '"')) { Mal "$($f.Name) usa @color/$c y no existe" }
  }
}

# ---------------------------------------------------------------- AndroidManifest
# Una clase puede vivir en cualquier archivo .kt (aqui, por ejemplo,
# AgentDeviceAdminReceiver esta dentro de DeviceAdmin.kt), asi que lo que se
# comprueba es que EXISTA la declaracion y que su package coincida con el
# nombre que declara el manifiesto: si el paquete no cuadra, el sistema no
# encuentra el componente y la app se cae al arrancarlo.
$declaradas = @{}
foreach ($f in $kt) {
  $pkg = [regex]::Match($kotlinFuentes[$f.FullName], '(?m)^package\s+([A-Za-z0-9_.]+)').Groups[1].Value
  foreach ($d in [regex]::Matches($kotlinFuentes[$f.FullName], '(?m)^\s*(?:\w+\s+)?(?:class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)')) {
    $declaradas[$d.Groups[1].Value] = $pkg
  }
}
$manifest = Leer 'android/app/src/main/AndroidManifest.xml'
foreach ($m in [regex]::Matches($manifest, 'android:name="\.([A-Za-z0-9_.]+)"')) {
  $clase = $m.Groups[1].Value
  $simple = ($clase -split '\.')[-1]
  $prefijo = ''
  if ($clase.Contains('.')) { $prefijo = $clase.Substring(0, $clase.LastIndexOf('.')) }
  $pkgEsperado = if ($prefijo) { 'com.locator.agent.' + $prefijo } else { 'com.locator.agent' }
  if (-not $declaradas.ContainsKey($simple)) {
    Mal "manifest declara .$clase y no hay ninguna clase $simple en el codigo"
  } elseif ($declaradas[$simple] -ne $pkgEsperado) {
    Mal "manifest declara .$clase (package $pkgEsperado) y $simple vive en $($declaradas[$simple])"
  } else {
    Bien "manifest $clase"
  }
}

# ---------------------------------------------------------------- Web: ids
function IdsDe([string]$htmlRel) {
  $txt = Leer $htmlRel
  $out = @()
  foreach ($m in [regex]::Matches($txt, 'id="([A-Za-z_][A-Za-z0-9_]*)"')) { $out += $m.Groups[1].Value }
  return $out
}
function UsadosEn([string]$jsRel) {
  $txt = Leer $jsRel
  $out = @()
  # Ojo: en PowerShell el backslash es literal y el unico escape es la tilde
  # invertida, asi que estos patrones van entre comillas simples (y por eso las
  # comillas de dentro se escriben dobles) para que el $ no se interprete.
  $patrones = @(
    '[$]\(''([A-Za-z_][A-Za-z0-9_]*)''\)',
    'getElementById\(''([A-Za-z_][A-Za-z0-9_]*)''\)'
  )
  foreach ($p in $patrones) {
    foreach ($m in [regex]::Matches($txt, $p)) { $out += $m.Groups[1].Value }
  }
  return ($out | Sort-Object -Unique)
}

$pares = @(
  @{ Js = 'web/admin.js'; Html = 'web/admin.html' },
  @{ Js = 'web/app.js'; Html = 'web/index.html' }
)
foreach ($p in $pares) {
  $ids = IdsDe $p.Html
  foreach ($id in UsadosEn $p.Js) {
    if ($ids -notcontains $id) { Mal "$($p.Js) usa el id '$id' y no esta en $($p.Html)" }
  }
  # onclick="fn(...)" del HTML debe existir como function en su JS
  $html = Leer $p.Html
  $js = Leer $p.Js
  foreach ($m in [regex]::Matches($html, 'on(?:click|change|input)="([A-Za-z_][A-Za-z0-9_]*)\s*\(')) {
    $fn = $m.Groups[1].Value
    if ($js -notmatch ('function\s+' + [regex]::Escape($fn) + '\b')) { Mal "$($p.Html) llama a $fn() y no esta definida en $($p.Js)" }
  }
  Bien "$($p.Js) -> $($p.Html) (ids y handlers)"
}

Write-Output ("Resumen: kotlin=$($kt.Count) ids=$($xmlNames.Count) strings=$($stringsDef.Count) fallos=$fail")
if ($fail -gt 0) { exit 1 }
