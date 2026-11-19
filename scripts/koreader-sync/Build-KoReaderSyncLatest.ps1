#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$OutputDirectory = '',
    [switch]$SkipTests,
    [switch]$KeepWorkspace
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$patchPath = Join-Path $PSScriptRoot 'koreader-sync.patch'
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $releaseFolderName = 'koreader-sync' + [char]0x7248
    $OutputDirectory = Join-Path $repoRoot (Join-Path 'dist' $releaseFolderName)
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)

$owner = 'LegadoTeam'
$repository = 'legado'
$userAgent = 'legado-koreader-sync-builder/1.0'
$latestReleaseUrl = "https://github.com/$owner/$repository/releases/latest"
$tempToken = [guid]::NewGuid().ToString('N').Substring(0, 8)
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('lks-' + $tempToken)
$tempRepo = Join-Path $tempRoot 'source'
$upstreamDownloads = Join-Path $tempRoot 'upstream-apk'
$worktreeAdded = $false
$scriptFailure = $null

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [string]$WorkingDirectory = $repoRoot,
        [string]$Description = $FilePath
    )

    Write-Host "==> $Description"
    Push-Location $WorkingDirectory
    try {
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "$Description failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

function Get-LatestReleaseTag {
    $headers = @{ 'User-Agent' = $userAgent }
    $response = Invoke-WebRequest -UseBasicParsing -MaximumRedirection 10 `
        -Headers $headers -Uri $latestReleaseUrl -TimeoutSec 30 -ErrorAction Stop

    $finalUri = $null
    if ($null -ne $response.BaseResponse -and $null -ne $response.BaseResponse.ResponseUri) {
        $finalUri = $response.BaseResponse.ResponseUri.AbsoluteUri
    }
    elseif ($null -ne $response.BaseResponse -and $null -ne $response.BaseResponse.RequestMessage -and
        $null -ne $response.BaseResponse.RequestMessage.RequestUri) {
        $finalUri = $response.BaseResponse.RequestMessage.RequestUri.AbsoluteUri
    }
    if ([string]::IsNullOrWhiteSpace([string]$finalUri)) {
        throw 'GitHub did not return a final latest-release URL.'
    }

    $uri = [Uri]([string]$finalUri)
    $match = [regex]::Match($uri.AbsolutePath, '/releases/tag/([^/]+)$')
    if (-not $match.Success) {
        throw "Could not parse the latest release tag from $location"
    }

    $tag = [Uri]::UnescapeDataString($match.Groups[1].Value)
    if ($tag -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$') {
        throw "Refusing an invalid release tag: $tag"
    }
    return $tag
}

function Get-ReleaseApkAssets {
    param([Parameter(Mandatory = $true)][string]$Tag)

    $encodedTag = [Uri]::EscapeDataString($Tag)
    $assetPage = "https://github.com/$owner/$repository/releases/expanded_assets/$encodedTag"
    $response = Invoke-WebRequest -UseBasicParsing -Headers @{ 'User-Agent' = $userAgent } `
        -Uri $assetPage -TimeoutSec 30

    $assets = @()
    foreach ($link in $response.Links) {
        $href = [string]$link.href
        if ($href -match '/releases/download/[^?]+$' -and $href.EndsWith('.apk', [StringComparison]::OrdinalIgnoreCase)) {
            if ($href.StartsWith('/')) {
                $href = "https://github.com$href"
            }
            elseif ($href -notmatch '^https?://') {
                $href = ([Uri]::new([Uri]$assetPage, $href)).AbsoluteUri
            }
            $assets += $href
        }
    }

    if ($assets.Count -eq 0) {
        throw "No APK assets were found on the release page for $Tag."
    }
    return @($assets | Sort-Object -Unique)
}

function Resolve-AndroidSdk {
    $localProperties = Join-Path $repoRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        $line = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($line) {
            $value = $line.Substring($line.IndexOf('=') + 1)
            $value = $value.Replace('\:', ':').Replace('\\', '\')
            if (Test-Path -LiteralPath $value) {
                return (Resolve-Path -LiteralPath $value).Path
            }
        }
    }

    foreach ($candidate in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw 'Android SDK was not found. Set ANDROID_SDK_ROOT or configure local.properties.'
}

function Find-LatestBuildTool {
    param(
        [Parameter(Mandatory = $true)][string]$SdkRoot,
        [Parameter(Mandatory = $true)][string]$ToolName
    )

    $buildToolsRoot = Join-Path $SdkRoot 'build-tools'
    if (-not (Test-Path -LiteralPath $buildToolsRoot)) {
        throw "Android build-tools directory does not exist: $buildToolsRoot"
    }

    $tool = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName $ToolName } |
        Where-Object { Test-Path -LiteralPath $_ } |
        Select-Object -First 1
    if (-not $tool) {
        throw "$ToolName was not found under $buildToolsRoot"
    }
    return $tool
}

try {
    if (-not (Test-Path -LiteralPath $patchPath)) {
        throw "KOReader sync patch was not found: $patchPath"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $repoRoot '.git'))) {
        throw "The script must be run from a Git checkout of Legado: $repoRoot"
    }
    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME) -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        throw 'JAVA_HOME must point to JDK 21.'
    }

    $sdkRoot = Resolve-AndroidSdk
    $env:ANDROID_SDK_ROOT = $sdkRoot
    $env:ANDROID_HOME = $sdkRoot
    New-Item -ItemType Directory -Path $tempRoot, $upstreamDownloads -Force | Out-Null

    $releaseTag = Get-LatestReleaseTag
    $shortVersion = $releaseTag.TrimStart('v')
    $deliveryVersion = "$shortVersion-koreader-sync"
    $numericVersionCode = $shortVersion -replace '[^0-9]', ''
    [long]$versionCodeOverride = 0
    if ($numericVersionCode.Length -lt 5 -or
        -not [long]::TryParse($numericVersionCode, [ref]$versionCodeOverride) -or
        $versionCodeOverride -gt [int]::MaxValue) {
        throw "Could not derive a valid Android versionCode from release tag: $releaseTag"
    }
    $deliveryFileName = "legado_app_${deliveryVersion}_releaseA.apk"
    $deliveryPath = Join-Path $OutputDirectory $deliveryFileName
    Write-Host "==> Latest GitHub release: $releaseTag"
    Write-Host "==> Delivery version: $deliveryVersion"
    Write-Host "==> Android versionCode: $versionCodeOverride"

    $releaseAssets = Get-ReleaseApkAssets -Tag $releaseTag
    foreach ($assetUrl in $releaseAssets) {
        $assetName = [IO.Path]::GetFileName(([Uri]$assetUrl).AbsolutePath)
        $assetTarget = Join-Path $upstreamDownloads $assetName
        Write-Host "    Downloading upstream APK: $assetName"
        Invoke-WebRequest -UseBasicParsing -Headers @{ 'User-Agent' = $userAgent } `
            -Uri $assetUrl -OutFile $assetTarget -TimeoutSec 300
        if ((Get-Item -LiteralPath $assetTarget).Length -le 0) {
            throw "Downloaded APK is empty: $assetTarget"
        }
    }

    Invoke-CheckedCommand -FilePath 'git' -Arguments @('fetch', 'origin', '--tags', '--force') `
        -Description 'Fetching upstream release tags'
    Invoke-CheckedCommand -FilePath 'git' -Arguments @('rev-parse', '--verify', "refs/tags/$releaseTag^{commit}") `
        -Description "Verifying source tag $releaseTag"

    Invoke-CheckedCommand -FilePath 'git' -Arguments @('worktree', 'add', '--detach', $tempRepo, $releaseTag) `
        -Description "Creating a clean build worktree for $releaseTag"
    $worktreeAdded = $true

    Invoke-CheckedCommand -FilePath 'git' -Arguments @('apply', '--3way', '--whitespace=nowarn', $patchPath) `
        -WorkingDirectory $tempRepo -Description 'Applying the existing KOReader sync implementation'

    $appBuildPath = Join-Path $tempRepo 'app\build.gradle'
    $appBuild = [IO.File]::ReadAllText($appBuildPath)
    $versionPattern = '(?m)^def version\s*=.*$'
    if (-not [regex]::IsMatch($appBuild, $versionPattern)) {
        throw 'Could not find the Legado version declaration in app/build.gradle.'
    }
    $versionReplacement = 'def version = "' + $deliveryVersion + '"'
    $appBuild = [regex]::Replace($appBuild, $versionPattern, $versionReplacement, 1)
    $versionCodePattern = '(?m)^(\s*versionCode\s*=\s*)versionCodeValue\s*$'
    if (-not [regex]::IsMatch($appBuild, $versionCodePattern)) {
        throw 'Could not find the Legado versionCode assignment in app/build.gradle.'
    }
    $versionCodeReplacement = '${1}' + [string]$versionCodeOverride
    $appBuild = [regex]::Replace($appBuild, $versionCodePattern, $versionCodeReplacement, 1)
    [IO.File]::WriteAllText($appBuildPath, $appBuild, [Text.UTF8Encoding]::new($false))

    $signingStoreFile = $env:RELEASE_STORE_FILE
    $signingStorePassword = $env:RELEASE_STORE_PASSWORD
    $signingKeyAlias = $env:RELEASE_KEY_ALIAS
    $signingKeyPassword = $env:RELEASE_KEY_PASSWORD
    if ([string]::IsNullOrWhiteSpace($signingStoreFile)) {
        $signingStoreFile = Join-Path $HOME '.android\debug.keystore'
        $signingStorePassword = 'android'
        $signingKeyAlias = 'androiddebugkey'
        $signingKeyPassword = 'android'
        Write-Host '==> Signing with the Android debug keystore'
    }
    else {
        Write-Host '==> Signing with RELEASE_STORE_FILE'
    }
    if (-not (Test-Path -LiteralPath $signingStoreFile)) {
        throw "Signing keystore was not found: $signingStoreFile"
    }
    foreach ($signingValue in @($signingStorePassword, $signingKeyAlias, $signingKeyPassword)) {
        if ([string]::IsNullOrWhiteSpace($signingValue)) {
            throw 'RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD must all be set.'
        }
    }

    $gradleArguments = @('--no-daemon', '--stacktrace')
    $gradleArguments += @(
        "-PRELEASE_STORE_FILE=$([IO.Path]::GetFullPath($signingStoreFile))",
        "-PRELEASE_STORE_PASSWORD=$signingStorePassword",
        "-PRELEASE_KEY_ALIAS=$signingKeyAlias",
        "-PRELEASE_KEY_PASSWORD=$signingKeyPassword"
    )
    if (-not $SkipTests) {
        $gradleArguments += @(':app:testAppReleaseUnitTest', '--tests', 'io.legado.app.help.koReader.*')
    }
    $gradleArguments += ':app:assembleAppRelease'
    Invoke-CheckedCommand -FilePath (Join-Path $tempRepo 'gradlew.bat') -Arguments $gradleArguments `
        -WorkingDirectory $tempRepo -Description "Building Legado $deliveryVersion"

    $apkCandidates = @(Get-ChildItem -LiteralPath (Join-Path $tempRepo 'app\build\outputs\apk\app\release') -Filter '*.apk' -File -ErrorAction Stop)
    if ($apkCandidates.Count -ne 1) {
        throw "Expected one release APK, found $($apkCandidates.Count)."
    }
    $builtApk = $apkCandidates[0].FullName

    Write-Host '==> Verifying APK metadata and version'
    $aapt = Find-LatestBuildTool -SdkRoot $sdkRoot -ToolName 'aapt.exe'
    $badgingLines = & $aapt dump badging $builtApk 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw 'aapt could not read the built APK.'
    }
    $badging = $badgingLines -join [Environment]::NewLine
    $packageMatch = [regex]::Match($badging, "package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'")
    if (-not $packageMatch.Success) {
        throw 'Could not parse the APK package metadata.'
    }
    if ($packageMatch.Groups[1].Value -ne 'com.legado.app.releaseA') {
        throw "Unexpected APK package: $($packageMatch.Groups[1].Value)"
    }
    if ($packageMatch.Groups[2].Value -ne [string]$versionCodeOverride) {
        throw "Unexpected APK versionCode: $($packageMatch.Groups[2].Value)"
    }
    if ($packageMatch.Groups[3].Value -ne $deliveryVersion) {
        throw "Unexpected APK versionName: $($packageMatch.Groups[3].Value)"
    }
    if ($badging -notmatch 'launchable-activity:') {
        throw 'The APK has no launchable activity.'
    }

    Write-Host '==> Verifying bundled KOReader sync resources'
    $resourceDump = & $aapt dump resources $builtApk 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw 'aapt could not read the built APK resources.'
    }
    $resourceText = $resourceDump -join [Environment]::NewLine
    foreach ($requiredResource in @('array/koreader_matching_title', 'id/menu_koreader_sync', 'string/koreader_account')) {
        if ($resourceText -notmatch [regex]::Escape($requiredResource)) {
            throw "The built APK is missing KOReader sync resource: $requiredResource"
        }
    }

    Write-Host '==> Verifying APK signature'
    $apksigner = Find-LatestBuildTool -SdkRoot $sdkRoot -ToolName 'apksigner.bat'
    $signatureOutput = & $apksigner verify --verbose --print-certs $builtApk 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw 'APK signature verification failed.'
    }
    if (($signatureOutput -join [Environment]::NewLine) -notmatch 'Verifies') {
        throw 'APK signature verification did not report a valid signature.'
    }

    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $partialPath = "$deliveryPath.partial"
    Copy-Item -LiteralPath $builtApk -Destination $partialPath -Force
    Move-Item -LiteralPath $partialPath -Destination $deliveryPath -Force

    $deliveryHash = (Get-FileHash -LiteralPath $deliveryPath -Algorithm SHA256).Hash
    Write-Host ''
    Write-Host 'Delivery verified:'
    Write-Host "  APK:          $deliveryPath"
    Write-Host "  Version:      $deliveryVersion"
    Write-Host "  Package:      com.legado.app.releaseA"
    Write-Host "  Release tag:  $releaseTag"
    Write-Host "  SHA-256:      $deliveryHash"
}
catch {
    $scriptFailure = $_
    throw
}
finally {
    if ($KeepWorkspace) {
        if (Test-Path -LiteralPath $tempRoot) {
            Write-Host "Temporary workspace kept at: $tempRoot"
        }
    }
    elseif (Test-Path -LiteralPath $tempRoot) {
        $resolvedTemp = [IO.Path]::GetFullPath($tempRoot)
        $systemTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
        if (-not $resolvedTemp.StartsWith($systemTemp + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($resolvedTemp) -notmatch '^lks-[0-9a-f]{8}$') {
            throw "Refusing to remove an unexpected temporary path: $resolvedTemp"
        }
        $previousErrorPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            if ($worktreeAdded) {
                & git -C $repoRoot worktree remove --force $tempRepo 2>&1 | Out-Null
            }
        }
        catch {
            Write-Warning "Git could not remove the temporary worktree; falling back to filesystem cleanup: $($_.Exception.Message)"
        }
        finally {
            $ErrorActionPreference = $previousErrorPreference
        }

        try {
            if (Test-Path -LiteralPath $resolvedTemp) {
                [IO.Directory]::Delete($resolvedTemp, $true)
            }
            & git -C $repoRoot worktree prune 2>&1 | Out-Null
        }
        catch {
            if ($null -eq $scriptFailure) {
                throw
            }
            Write-Warning "Temporary workspace cleanup also failed: $($_.Exception.Message)"
        }
    }
}
