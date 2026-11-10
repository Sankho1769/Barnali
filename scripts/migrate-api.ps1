[CmdletBinding()]
param(
  [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

if (-not $Apply) {
  Write-Output 'Preflight only: no database connection or migrations were attempted.'
  Write-Output 'After explicit approval, run: .\scripts\migrate-api.ps1 -Apply'
  return
}

$envFile = Join-Path $root '.env'
if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
  throw 'BARNALI environment file was not found. No migrations were run.'
}

. (Join-Path $PSScriptRoot 'Import-BarnaliEnvironment.ps1')

# Preserve this PowerShell session's environment after importing .env. Values remain
# in process memory only and are never echoed by this script.
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
      throw 'Set all MIGRATION_DATABASE_* settings and BARNALI_EXPECTED_SUPABASE_PROJECT_REF explicitly. The migration command never falls back to runtime credentials.'
    }
  }
  if ($env:MIGRATION_DATABASE_USERNAME -ceq $env:DATABASE_USERNAME) {
    throw 'Configure a migration principal distinct from the runtime principal. No migrations were run.'
  }
  Write-Output 'Target guard: aws-0-ap-northeast-2.pooler.supabase.com:5432 / postgres / barnali; TLS is required.'
  Write-Output 'Credentials and project-scoped username are not displayed.'
  $confirmation = Read-Host 'Type APPLY BARNALI PRIVATE SCHEMA MIGRATIONS to continue'
  if ($confirmation -cne 'APPLY BARNALI PRIVATE SCHEMA MIGRATIONS') {
    Write-Output 'Confirmation did not match. No migrations were run.'
    return
  }

  $env:BARNALI_MIGRATION_APPROVED = 'true'
  Push-Location (Join-Path $root 'apps/api')
  try {
    & mvn -q '-Dspring-boot.run.main-class=com.barnali.api.migration.MigrationOnlyApplication' spring-boot:run
    if ($LASTEXITCODE -ne 0) {
      throw 'The migration-only command failed. Sensitive exception messages and credentials are suppressed.'
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
