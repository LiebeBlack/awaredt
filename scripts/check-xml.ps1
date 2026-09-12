$ErrorActionPreference = 'Stop'
$fail = 0
Get-ChildItem -Recurse android -Filter *.xml | ForEach-Object {
  $x = New-Object Xml.XmlDocument
  try {
    $x.Load($_.FullName)
    Write-Output ("OK   " + $_.FullName)
  } catch {
    $fail++
    Write-Output ("FAIL " + $_.FullName + " -> " + $_.Exception.Message)
  }
}
Write-Output ("Total files: " + (Get-ChildItem -Recurse android -Filter *.xml).Count + " failures: " + $fail)
