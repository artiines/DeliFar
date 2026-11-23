param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$jdkPath = $env:JAVA_HOME
if (-not $jdkPath -or -not (Test-Path -LiteralPath (Join-Path $jdkPath 'bin/java.exe'))) {
    $jdkPath = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
}
if (-not (Test-Path -LiteralPath (Join-Path $jdkPath 'bin/java.exe'))) { throw 'Set JAVA_HOME to a working JDK 17 or newer.' }
$env:JAVA_HOME = $jdkPath
$tasks = @(':app:assembleDebug', ':app:lintDebug')
if (-not $SkipTests) { $tasks += ':app:testDebugUnitTest' }
& .\gradlew.bat @tasks --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Build or verification failed. See Gradle output.' }
$destination = Join-Path $PSScriptRoot 'output'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'app/build/outputs/apk/debug/app-debug.apk') -Destination (Join-Path $destination 'DeliFar.apk')
$apk = Get-Item -LiteralPath (Join-Path $destination 'DeliFar.apk')
if ($apk.Length -eq 0) { throw 'APK is empty.' }
Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256
Write-Output ('APK: ' + $apk.FullName + ' (' + [math]::Round($apk.Length / 1MB, 1) + ' MiB)')
