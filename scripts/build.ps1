$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
New-Item -ItemType Directory -Force build/classes | Out-Null
$sources = Get-ChildItem -Recurse src/main/java -Filter *.java | ForEach-Object { $_.FullName }
& javac --release 17 --add-modules jdk.httpserver -d build/classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
Copy-Item src/main/resources/* build/classes/
& jar --create --file build/switchyard.jar --main-class dev.switchyard.Main -C build/classes .
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed' }
Write-Host 'Built build/switchyard.jar'
