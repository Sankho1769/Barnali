function Import-BarnaliEnvironment {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$Path,

    [Parameter()]
    [string[]]$Names
  )

  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    throw 'BARNALI environment file was not found.'
  }

  $allowedNames = $null
  if ($PSBoundParameters.ContainsKey('Names')) {
    $allowedNames = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($allowedName in $Names) { [void]$allowedNames.Add($allowedName) }
  }

  $lineNumber = 0
  foreach ($line in [IO.File]::ReadAllLines([IO.Path]::GetFullPath($Path))) {
    $lineNumber++
    if ($lineNumber -eq 1) {
      $line = $line.TrimStart([char]0xFEFF)
    }

    if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) {
      continue
    }

    $separator = $line.IndexOf('=')
    if ($separator -lt 1) {
      continue
    }

    $name = $line.Substring(0, $separator).Trim()
    if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
      throw "Invalid environment-variable name on line $lineNumber."
    }
    if ($null -ne $allowedNames -and -not $allowedNames.Contains($name)) {
      continue
    }

    # Values are data, not PowerShell syntax. Keep every character after the first '='.
    # Quotes and inline '#' characters are literal; this is intentionally not a shell parser.
    $value = $line.Substring($separator + 1)
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
  }
}
