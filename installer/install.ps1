# jrun installer for Windows (x64 / ARM64):
#
#   powershell -ExecutionPolicy ByPass -c "irm https://github.com/sofn/jrun-gradle-plugin/releases/latest/download/install.ps1 | iex"
#
# Installs jrun.bat to %USERPROFILE%\.local\bin (added to the user PATH) and the Gradle
# wrapper template used by `jrun init` to %LOCALAPPDATA%\jrun\wrapper. Every download is
# checked against SHA256SUMS.
#
# Environment: JRUN_VERSION, JRUN_INSTALL_DIR, JRUN_HOME, JRUN_DOWNLOAD_BASE (see install.sh)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repo = 'sofn/jrun-gradle-plugin'
$version = if ($env:JRUN_VERSION) { $env:JRUN_VERSION } else { 'latest' }
if ($env:JRUN_DOWNLOAD_BASE) {
    $base = $env:JRUN_DOWNLOAD_BASE.TrimEnd('/')
} elseif ($version -eq 'latest') {
    $base = "https://github.com/$repo/releases/latest/download"
} else {
    $base = "https://github.com/$repo/releases/download/v$($version.TrimStart('v'))"
}
$binDir = if ($env:JRUN_INSTALL_DIR) { $env:JRUN_INSTALL_DIR } else { Join-Path $HOME '.local\bin' }
$dataDir = if ($env:JRUN_HOME) { $env:JRUN_HOME } else { Join-Path $env:LOCALAPPDATA 'jrun' }

$arch = $env:PROCESSOR_ARCHITECTURE
if (@('AMD64', 'ARM64') -notcontains $arch) { throw "jrun install: unsupported architecture $arch" }

# Windows PowerShell 5.1 does not enable TLS 1.2 by default
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$tmp = Join-Path ([IO.Path]::GetTempPath()) ('jrun-' + [Guid]::NewGuid())
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    function Get-Asset([string] $name) {
        $dest = Join-Path $tmp $name
        Invoke-WebRequest -UseBasicParsing -Uri "$base/$name" -OutFile $dest
        return $dest
    }
    $sums = @{}
    foreach ($line in Get-Content (Get-Asset 'SHA256SUMS')) {
        $parts = $line -split '\s+', 2
        if ($parts.Count -eq 2) { $sums[$parts[1].TrimStart('*')] = $parts[0].ToLowerInvariant() }
    }
    foreach ($name in 'jrun.bat', 'gradlew', 'gradlew.bat', 'gradle-wrapper.jar', 'gradle-wrapper.properties') {
        $file = Get-Asset $name
        if (-not $sums.ContainsKey($name)) { throw "jrun install: $name is missing from SHA256SUMS" }
        $actual = (Get-FileHash -Algorithm SHA256 -Path $file).Hash.ToLowerInvariant()
        if ($actual -ne $sums[$name]) { throw "jrun install: checksum mismatch for $name" }
    }
    $wrapperDir = Join-Path $dataDir 'wrapper'
    New-Item -ItemType Directory -Force -Path $binDir, (Join-Path $wrapperDir 'gradle\wrapper') | Out-Null
    Copy-Item (Join-Path $tmp 'jrun.bat') (Join-Path $binDir 'jrun.bat') -Force
    Copy-Item (Join-Path $tmp 'gradlew'), (Join-Path $tmp 'gradlew.bat') $wrapperDir -Force
    Copy-Item (Join-Path $tmp 'gradle-wrapper.jar'), (Join-Path $tmp 'gradle-wrapper.properties') (Join-Path $wrapperDir 'gradle\wrapper') -Force
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if (-not $userPath) { $userPath = '' }
if (($userPath -split ';') -notcontains $binDir) {
    [Environment]::SetEnvironmentVariable('Path', ($userPath.TrimEnd(';') + ';' + $binDir).TrimStart(';'), 'User')
    Write-Host "Added $binDir to your user PATH (open a new terminal to use it)."
}
Write-Host "Installed jrun to $binDir\jrun.bat"
Write-Host 'Get started: jrun init my-app; cd my-app; jrun'
