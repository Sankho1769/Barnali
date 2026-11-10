$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
  throw 'Create .env from .env.example and configure the database and required secrets first.'
}

. (Join-Path $PSScriptRoot 'Import-BarnaliEnvironment.ps1')
Import-BarnaliEnvironment -Path $envFile

# Make this script an ordinary API-only entry point even if an environment file
# contains an opt-in runner flag. The dedicated scripts set these options only
# inside their isolated JVM process.
$startupOverrides = @{
  SPRING_FLYWAY_ENABLED = 'false'
  BARNALI_MIGRATION_RUNNER_ENABLED = 'false'
  BARNALI_OWNER_BOOTSTRAP_ENABLED = 'false'
}
$previousStartupOverrides = @{}
foreach ($name in $startupOverrides.Keys) {
  $previousStartupOverrides[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
  [Environment]::SetEnvironmentVariable($name, $startupOverrides[$name], 'Process')
}

Push-Location (Join-Path $root 'apps/api')
try {
  mvn '-Dspring-boot.run.main-class=com.barnali.api.BarnaliApiApplication' spring-boot:run
} finally {
  Pop-Location
  foreach ($name in $startupOverrides.Keys) {
    $previous = $previousStartupOverrides[$name]
    if ($null -eq $previous) {
      Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
    } else {
      [Environment]::SetEnvironmentVariable($name, $previous, 'Process')
    }
  }
}
