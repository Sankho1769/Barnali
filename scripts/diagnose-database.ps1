[CmdletBinding()]
param(
  [switch]$TestConnection
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$apiDirectory = Join-Path $root 'apps/api'
$pomPath = Join-Path $apiDirectory 'pom.xml'
$envFile = Join-Path $root '.env'

# Compile only. This does not invoke Spring Boot, Flyway, or application runners.
Push-Location $apiDirectory
try {
  $null = @(& mvn -q -DskipTests compile 2>&1)
  $compileExitCode = $LASTEXITCODE
  if ($compileExitCode -ne 0) {
    throw "BARNALI diagnostic compilation failed with exit code $compileExitCode."
  }
} finally {
  Pop-Location
}

$pomText = [IO.File]::ReadAllText($pomPath)
$parentVersionMatch = [regex]::Match($pomText, '(?s)<parent>.*?<version>\s*([^<\s]+)\s*</version>')
if (-not $parentVersionMatch.Success) {
  throw 'Could not determine the Spring Boot dependency-management version.'
}

$mavenRepository = Join-Path $env:USERPROFILE '.m2/repository'
$bootVersion = $parentVersionMatch.Groups[1].Value
$bootBom = Join-Path $mavenRepository "org/springframework/boot/spring-boot-dependencies/$bootVersion/spring-boot-dependencies-$bootVersion.pom"
if (-not (Test-Path -LiteralPath $bootBom -PathType Leaf)) {
  throw 'The local Spring Boot dependency-management file is unavailable; no JDBC driver was selected.'
}

$bomText = [IO.File]::ReadAllText($bootBom)
$driverVersionMatch = [regex]::Match($bomText, '<postgresql\.version>\s*([^<\s]+)\s*</postgresql\.version>')
if (-not $driverVersionMatch.Success) {
  throw 'Could not determine the project-managed PostgreSQL JDBC driver version.'
}

$driverVersion = $driverVersionMatch.Groups[1].Value
$driverJar = Join-Path $mavenRepository "org/postgresql/postgresql/$driverVersion/postgresql-$driverVersion.jar"
if (-not (Test-Path -LiteralPath $driverJar -PathType Leaf)) {
  throw 'The project-managed PostgreSQL JDBC driver is not present in the local Maven repository.'
}

. (Join-Path $PSScriptRoot 'Import-BarnaliEnvironment.ps1')
Import-BarnaliEnvironment -Path $envFile

$classes = Join-Path $apiDirectory 'target/classes'
$classpath = "$classes;$driverJar"
$mainClass = 'com.barnali.api.diagnostics.DatabaseDiagnosticMain'

function Invoke-SafeDiagnostic([string[]]$Arguments) {
  $output = @(& java -cp $classpath $mainClass @Arguments 2>$null)
  if ($LASTEXITCODE -ne 0) {
    throw "The standalone diagnostic process failed with exit code $LASTEXITCODE."
  }

  $safe = @()
  foreach ($line in $output) {
    if ($line -match '^(env\.(DATABASE_URL|DATABASE_USERNAME|DATABASE_PASSWORD|APP_SECURITY_HMAC_KEY|OWNER_EMAIL|OWNER_BOOTSTRAP_PASSWORD)=(present|absent)|spring_datasource_mapping=DATABASE_URL,DATABASE_USERNAME,DATABASE_PASSWORD|hmac_minimum_32_utf8_bytes=(True|False)|jdbc_url_parse=(PASS|FAIL)(; reason=invalid_postgresql_jdbc_url)?|jdbc_host=[A-Za-z0-9.-]+|jdbc_port=[0-9]+|jdbc_database=[A-Za-z0-9_.-]*|jdbc_sslmode=(missing|invalid|disable|allow|prefer|require|verify-ca|verify-full)|jdbc_credentials_embedded=(True|False)|jdbc_url_fragment_present=(True|False)|jdbc_separate_credentials_conflict=(True|False)|session_pooler_host_shape=(True|False)|session_pooler_username_shape=(True|False)|postgres_connection=(NOT_REQUESTED|PASS; select_1=PASS; ssl_enforced=true|FAIL; .+|SKIPPED; reason=[a-z_]+))$') {
      $safe += $line
    }
  }
  return $safe
}

$report = @(Invoke-SafeDiagnostic -Arguments @())
$report | Where-Object { $_ -ne 'postgres_connection=NOT_REQUESTED' } | ForEach-Object { Write-Output $_ }

$databaseHost = $null
$port = $null
foreach ($line in $report) {
  if ($line -match '^jdbc_host=([A-Za-z0-9.-]+)$') { $databaseHost = $Matches[1] }
  if ($line -match '^jdbc_port=([0-9]+)$') { $port = [int]$Matches[1] }
}

if ($databaseHost -and $port) {
  $dnsPassed = $false
  try {
    $answers = @(Resolve-DnsName -Name $databaseHost -ErrorAction Stop)
    $dnsPassed = $answers.Count -gt 0
  } catch {
    $dnsPassed = $false
  }

  if ($dnsPassed) {
    Write-Output "powershell_dns=PASS; host=$databaseHost"
  } else {
    Write-Output "powershell_dns=FAIL; host=$databaseHost"
  }

  $tcpPassed = $false
  try {
    $tcpPassed = [bool](Test-NetConnection -ComputerName $databaseHost -Port $port `
      -InformationLevel Quiet -WarningAction SilentlyContinue -ErrorAction Stop)
  } catch {
    $tcpPassed = $false
  }
  Write-Output "powershell_tcp_$port=$(if ($tcpPassed) { 'PASS' } else { 'FAIL' })"
} else {
  Write-Output 'powershell_dns=NOT_ATTEMPTED; reason=no_parsed_host'
  Write-Output 'powershell_tcp=NOT_ATTEMPTED; reason=no_parsed_endpoint'
}

if ($TestConnection) {
  $connectionReport = @(Invoke-SafeDiagnostic -Arguments @('--connect'))
  $connectionReport | Where-Object { $_ -match '^postgres_connection=' } | ForEach-Object { Write-Output $_ }
} else {
  Write-Output 'postgres_connection=NOT_REQUESTED; rerun with -TestConnection to opt in'
}
