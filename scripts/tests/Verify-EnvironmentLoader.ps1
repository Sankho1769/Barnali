$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\Import-BarnaliEnvironment.ps1')

$tempFile = Join-Path ([IO.Path]::GetTempPath()) ('barnali-env-test-' + [guid]::NewGuid().ToString('N') + '.env')
$cases = [ordered]@{
  BARNALI_TEST_AMPERSAND = 'left&right'
  BARNALI_TEST_HASH = 'left#right'
  BARNALI_TEST_PERCENT = 'left%right'
  BARNALI_TEST_PLUS = 'left+right'
  BARNALI_TEST_EQUALS = 'left=middle=right'
  BARNALI_TEST_COMBINED = 'a&b#c%d+e=f'
  BARNALI_TEST_LITERAL_QUOTE = '"left&right"'
}

$previous = @{}
foreach ($name in $cases.Keys) {
  $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
  $lines = foreach ($entry in $cases.GetEnumerator()) { "$($entry.Key)=$($entry.Value)" }
  [IO.File]::WriteAllLines($tempFile, $lines, [Text.UTF8Encoding]::new($false))
  Import-BarnaliEnvironment -Path $tempFile

  foreach ($entry in $cases.GetEnumerator()) {
    $actual = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
    if ($actual -cne $entry.Value) {
      throw 'Environment loader did not preserve a test value exactly.'
    }
  }

  Write-Output 'environment_loader_special_character_test=PASS'
} finally {
  foreach ($name in $cases.Keys) {
    [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
  }
  Remove-Item -LiteralPath $tempFile -Force -ErrorAction SilentlyContinue
}
