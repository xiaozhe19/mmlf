$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$destination = Join-Path $root 'mmlf/mods'
New-Item -ItemType Directory -Force $destination | Out-Null
$records = @()
foreach ($version in @('Zsfvgzu6','StnP0RNi')) {
    $metadata = Invoke-RestMethod -Uri "https://api.modrinth.com/v2/version/$version"
    if ($metadata.game_versions -notcontains '1.21.1') { throw "Not a 1.21.1 release: $version" }
    $file = $metadata.files | Where-Object primary | Select-Object -First 1
    if (-not $file -or ([uri]$file.url).Scheme -ne 'https' -or ([uri]$file.url).Host -ne 'cdn.modrinth.com') { throw 'Unexpected download source' }
    $target = Join-Path $destination ([IO.Path]::GetFileName($file.filename))
    Invoke-WebRequest -Uri $file.url -OutFile $target
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA512).Hash.ToLower() -ne $file.hashes.sha512) { throw 'Mod checksum mismatch' }
    $records += [pscustomobject]@{version=$version; filename=$file.filename; url=$file.url; sha512=$file.hashes.sha512; loaders=$metadata.loaders; dependencies=$metadata.dependencies}
}
$records | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 (Join-Path $destination 'manifest.json')
Write-Output 'Verified Minecraft 1.21.1 demo mods downloaded.'
