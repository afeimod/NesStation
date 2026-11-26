param(
    [Parameter(Mandatory = $true)]
    [string]$Url,
    [Parameter(Mandatory = $true)]
    [string]$OutputPath
)

$ErrorActionPreference = "Stop"
$mirrorUrl = if ($Url.StartsWith("https://github.com/", [StringComparison]::OrdinalIgnoreCase)) {
    "https://ghproxy.net/$Url"
} else {
    $Url
}

$parent = Split-Path -Parent $OutputPath
if ($parent) {
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
}

& curl.exe --fail --location --retry 3 --retry-all-errors --output $OutputPath $mirrorUrl
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
