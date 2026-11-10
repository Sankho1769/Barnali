[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
  throw 'BARNALI environment file was not found. No database diagnostics were run.'
}

. (Join-Path $PSScriptRoot 'Import-BarnaliEnvironment.ps1')

# Restore only the process variables this script may replace. Values are never echoed.
$environmentNames = [System.Collections.Generic.List[string]]::new()
foreach ($line in [IO.File]::ReadAllLines([IO.Path]::GetFullPath($envFile))) {
  $candidate = $line.TrimStart([char]0xFEFF)
  if ([string]::IsNullOrWhiteSpace($candidate) -or $candidate.TrimStart().StartsWith('#')) { continue }
  $separator = $candidate.IndexOf('=')
  if ($separator -lt 1) { continue }
  $name = $candidate.Substring(0, $separator).Trim()
  if ($name -match '^[A-Za-z_][A-Za-z0-9_]*$' -and -not $environmentNames.Contains($name)) {
    $environmentNames.Add($name)
  }
}
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
  $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$previousApprovalFlag = [Environment]::GetEnvironmentVariable('BARNALI_MIGRATION_APPROVED', 'Process')

try {
  Import-BarnaliEnvironment -Path $envFile -Names @(
    'DATABASE_URL', 'DATABASE_USERNAME', 'DATABASE_PASSWORD',
    'MIGRATION_DATABASE_URL', 'MIGRATION_DATABASE_USERNAME', 'MIGRATION_DATABASE_PASSWORD',
    'BARNALI_EXPECTED_SUPABASE_PROJECT_REF'
  )
  foreach ($name in @('MIGRATION_DATABASE_URL', 'MIGRATION_DATABASE_USERNAME', 'MIGRATION_DATABASE_PASSWORD',
      'BARNALI_EXPECTED_SUPABASE_PROJECT_REF')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
      throw 'Set all MIGRATION_DATABASE_* settings and BARNALI_EXPECTED_SUPABASE_PROJECT_REF explicitly. The migration diagnostic never falls back to runtime credentials.'
    }
  }
  if ($env:MIGRATION_DATABASE_USERNAME -ceq $env:DATABASE_USERNAME) {
    throw 'Configure a migration principal distinct from the runtime principal. No database diagnostics were run.'
  }
  # This entry point ignores the approval flag in diagnostic mode. Remove it as an
  # additional guard so this script cannot accidentally authorize the apply path.
  Remove-Item -LiteralPath 'Env:BARNALI_MIGRATION_APPROVED' -ErrorAction SilentlyContinue
  Write-Output 'Diagnostic-only: checks datasource/TLS, Flyway history, V1/V2 schema objects, duplicate pending reports, and validation metadata.'
  Write-Output 'No migration, owner bootstrap, web server, or database write is run.'

  Push-Location (Join-Path $root 'apps/api')
  try {
    & mvn -q '-Dspring-boot.run.main-class=com.barnali.api.migration.MigrationOnlyApplication' `
      '-Dspring-boot.run.arguments=--barnali.migration.mode=diagnostic' spring-boot:run
    if ($LASTEXITCODE -ne 0) {
      throw 'Read-only migration diagnostics failed. Sensitive exception messages and credentials are suppressed.'
    }
  } finally {
    Pop-Location
  }
} finally {
  if ($null -eq $previousApprovalFlag) {
    Remove-Item -LiteralPath 'Env:BARNALI_MIGRATION_APPROVED' -ErrorAction SilentlyContinue
  } else {
    [Environment]::SetEnvironmentVariable('BARNALI_MIGRATION_APPROVED', $previousApprovalFlag, 'Process')
  }
  foreach ($name in $environmentNames) {
    $previous = $previousEnvironment[$name]
    if ($null -eq $previous) {
      Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
    } else {
      [Environment]::SetEnvironmentVariable($name, $previous, 'Process')
    }
  }
}
