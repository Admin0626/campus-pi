[CmdletBinding()]
param(
    [ValidateSet('web', 'backend', 'frontend')]
    [string]$Mode = 'web',
    [string]$Config = '',
    [switch]$SkipBuild,
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

function Find-Tool([string]$Name) {
    $command = Get-Command $Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $command) { throw "Required command not found: $Name. Add it to PATH first." }
    return $command.Source
}

function Invoke-Tool([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed with exit code $LASTEXITCODE. Startup stopped." }
}

function Assert-Java17([string]$Executable) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $Executable
    $info.Arguments = '-version'
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($info)
    $versionText = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    $code = $process.ExitCode
    $process.Dispose()
    if ($code -ne 0 -or $versionText -notmatch 'version\s+"17(?:\.|\+|")') {
        throw 'Java 17 is required. Set JAVA_HOME to your JDK 17 installation.'
    }
}

try {
    if ($SkipBuild -and $Mode -ne 'web') { throw '-SkipBuild is only supported with -Mode web.' }
    Push-Location $repoRoot
    try {
        if ($Mode -ne 'frontend') {
            if ($env:JAVA_HOME) {
                $javaBin = Join-Path $env:JAVA_HOME 'bin'
                if (-not (Test-Path (Join-Path $javaBin 'java.exe') -PathType Leaf)) {
                    throw 'JAVA_HOME does not contain bin/java.exe. Select a JDK 17 installation.'
                }
                $env:Path = "$javaBin;$env:Path"
            }
            $java = Find-Tool 'java.exe'
            Assert-Java17 $java
            # Maven uses JAVA_HOME independently from PATH. Keep both on this verified JDK.
            $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java)
            if ($Mode -eq 'backend' -or -not $SkipBuild) { $maven = Find-Tool 'mvn.cmd' }
            if (-not $Config) { $Config = Join-Path $repoRoot 'backend/src/main/resources/application.yml' }
            if (-not (Test-Path -LiteralPath $Config -PathType Leaf)) {
                throw 'Copy backend/src/main/resources/application.example.yml to application.yml and fill your database settings.'
            }
            # A file URI keeps spaces encoded through Maven's additional argument parsing.
            $configUri = [System.Uri]::new((Resolve-Path -LiteralPath $Config).Path).AbsoluteUri
            $configArgument = "--spring.config.additional-location=$configUri"
        }
        if ($Mode -eq 'frontend' -or ($Mode -eq 'web' -and -not $SkipBuild)) {
            $node = Find-Tool 'node.exe'
            $npm = Find-Tool 'npm.cmd'
            $nodeVersion = (& $node --version).Trim().TrimStart('v')
            if ($LASTEXITCODE -ne 0 -or [version]$nodeVersion -lt [version]'22.12.0') {
                throw 'Node.js 22.12 or newer is required.'
            }
        }
        $jar = Join-Path $repoRoot 'backend/target/campus-pi-server-0.0.1-SNAPSHOT.jar'
        if ($Mode -eq 'web' -and $SkipBuild -and -not (Test-Path -LiteralPath $jar -PathType Leaf)) {
            throw 'Built JAR not found. Run without -SkipBuild first.'
        }
        if ($Check) {
            Write-Host "PASS: $Mode prerequisites found. Config values, database and ports were not tested."
            exit 0
        }
        switch ($Mode) {
            'frontend' {
                if (-not (Test-Path 'frontend/node_modules/.package-lock.json' -PathType Leaf)) {
                    Invoke-Tool $npm @('--prefix', 'frontend', 'ci')
                }
                Invoke-Tool $npm @('--prefix', 'frontend', 'run', 'dev')
            }
            'backend' {
                $runArguments = '-Dspring-boot.run.arguments=' + $configArgument
                Invoke-Tool $maven @('-f', 'backend/pom.xml', 'spring-boot:run', $runArguments)
            }
            'web' {
                if (-not $SkipBuild) {
                    Invoke-Tool $npm @('--prefix', 'frontend', 'ci')
                    Invoke-Tool $npm @('--prefix', 'frontend', 'run', 'build')
                    Invoke-Tool $maven @('-B', '-f', 'backend/pom.xml', 'clean', 'package', '-DskipTests')
                }
                Invoke-Tool $java @('-jar', $jar, $configArgument)
            }
        }
    }
    finally { Pop-Location }
}
catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
