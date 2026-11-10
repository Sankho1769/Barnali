[CmdletBinding()]
param(
  [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

if (-not $Apply) {
  Write-Output 'No database connection or owner provisioning was attempted.'
  Write-Output 'After migrations and runtime-role grants are verified, run: .\scripts\bootstrap-owner.ps1 -Apply'
  return
}

$envFile = Join-Path $root '.env'
if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
  throw 'BARNALI environment file was not found. No owner was provisioned.'
}
if ([string]::IsNullOrWhiteSpace($env:OWNER_BOOTSTRAP_PASSWORD)) {
  throw 'Inject OWNER_BOOTSTRAP_PASSWORD into this PowerShell process from a secret manager first. It is not loaded from .env.'
}

. (Join-Path $PSScriptRoot 'Import-BarnaliEnvironment.ps1')
$namesToImport = @('OWNER_PROVISIONER_DATABASE_URL', 'OWNER_PROVISIONER_DATABASE_USERNAME',
  'OWNER_PROVISIONER_DATABASE_PASSWORD', 'OWNER_EMAIL', 'BARNALI_EXPECTED_SUPABASE_PROJECT_REF')
$previousEnvironment = @{}
foreach ($name in $namesToImport) {
  $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$previousApprovalFlag = [Environment]::GetEnvironmentVariable('BARNALI_OWNER_BOOTSTRAP_APPROVED', 'Process')
$bootstrapSucceeded = $false

try {
  Import-BarnaliEnvironment -Path $envFile -Names $namesToImport
  if ([string]::IsNullOrWhiteSpace($env:OWNER_PROVISIONER_DATABASE_URL) -or
      [string]::IsNullOrWhiteSpace($env:OWNER_PROVISIONER_DATABASE_USERNAME) -or
      [string]::IsNullOrWhiteSpace($env:OWNER_PROVISIONER_DATABASE_PASSWORD) -or
      [string]::IsNullOrWhiteSpace($env:BARNALI_EXPECTED_SUPABASE_PROJECT_REF) -or
      [string]::IsNullOrWhiteSpace($env:OWNER_EMAIL)) {
    throw 'Owner-provisioner database settings, BARNALI_EXPECTED_SUPABASE_PROJECT_REF, and OWNER_EMAIL must be configured. No owner was provisioned.'
  }
  Write-Output 'Owner provisioning uses the separate execute-only principal and performs a read-only target, TLS, role, migration, and owner-state preflight first.'
  Write-Output 'Only a consistent state with no existing OWNER can create the initial owner; existing OWNER data is never replaced.'
  $confirmation = Read-Host 'Type CREATE BARNALI OWNER to authorize the one-shot operation'
  if ($confirmation -cne 'CREATE BARNALI OWNER') {
    Write-Output 'Confirmation did not match. No owner was provisioned.'
    return
  }

  $env:BARNALI_OWNER_BOOTSTRAP_APPROVED = 'true'
  Push-Location (Join-Path $root 'apps/api')
  try {
    & mvn -q '-Dspring-boot.run.main-class=com.barnali.api.auth.OwnerBootstrapApplication' spring-boot:run
    if ($LASTEXITCODE -ne 0) {
      throw 'The isolated owner provisioning command failed. Sensitive exception messages and credentials are suppressed.'
    }
    $bootstrapSucceeded = $true
  } finally {
    Pop-Location
  }
} finally {
  if ($null -eq $previousApprovalFlag) {
    Remove-Item -LiteralPath 'Env:BARNALI_OWNER_BOOTSTRAP_APPROVED' -ErrorAction SilentlyContinue
  } else {
    [Environment]::SetEnvironmentVariable('BARNALI_OWNER_BOOTSTRAP_APPROVED', $previousApprovalFlag, 'Process')
  }
  foreach ($name in $namesToImport) {
    $previous = $previousEnvironment[$name]
    if ($null -eq $previous) {
      Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
    } else {
      [Environment]::SetEnvironmentVariable($name, $previous, 'Process')
    }
  }
  if ($bootstrapSucceeded) {
    Remove-Item -LiteralPath 'Env:OWNER_BOOTSTRAP_PASSWORD' -ErrorAction SilentlyContinue
  }
}
