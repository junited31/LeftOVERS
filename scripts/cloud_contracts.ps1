$ErrorActionPreference = 'Stop'

function Assert-Utf8SecretFile {
    param([Parameter(Mandatory)][string]$Path)

    try {
        $text = [System.Text.UTF8Encoding]::new($false, $true).GetString([System.IO.File]::ReadAllBytes($Path))
    }
    catch {
        throw 'Secret file must contain valid nonblank UTF-8.'
    }
    if ([string]::IsNullOrWhiteSpace($text)) { throw 'Secret file must contain valid nonblank UTF-8.' }
}

function Assert-BillingEnabled {
    param([Parameter(Mandatory)][string]$Value, [Parameter(Mandatory)][string]$ProjectId)

    if ($Value.Trim() -ine 'true') {
        throw "gcloud billing projects link $ProjectId --billing-account=<CALLER_SUPPLIED_ID>"
    }
}

function Assert-FirebaseConfig {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$ProjectId,
        [Parameter(Mandatory)][string]$PackageName
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Firebase config is missing: $Path"
    }
    $config = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    if ($config.project_info.project_id -cne $ProjectId) {
        throw "Firebase config project must be $ProjectId."
    }
    $packages = @($config.client | ForEach-Object { $_.client_info.android_client_info.package_name })
    if ($packages.Count -ne 1 -or $packages[0] -cne $PackageName) {
        throw "Firebase config must contain exactly package $PackageName."
    }
}

function Assert-AndroidKeyRestrictions {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$PackageName,
        [Parameter(Mandatory)][string]$Sha1
    )

    $key = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    $applications = @($key.restrictions.androidKeyRestrictions.allowedApplications)
    if ($applications.Count -ne 1 -or
        $applications[0].packageName -cne $PackageName -or
        $applications[0].sha1Fingerprint.Trim().Replace(':', '').ToUpperInvariant() -cne $Sha1.Trim().Replace(':', '').ToUpperInvariant()) {
        throw "Android key restriction must contain exactly $PackageName and the demo SHA-1."
    }

    $expected = @('identitytoolkit.googleapis.com', 'securetoken.googleapis.com')
    $actual = @($key.restrictions.apiTargets | ForEach-Object { $_.service })
    $difference = @(Compare-Object -ReferenceObject $expected -DifferenceObject $actual)
    if ($actual.Count -ne $expected.Count -or $difference.Count -ne 0) {
        throw "API restrictions must equal {identitytoolkit.googleapis.com, securetoken.googleapis.com}."
    }
}

function Get-DebugSigningSha1 {
    param([Parameter(Mandatory)][string]$SigningReport)

    $debugBlock = [regex]::Match($SigningReport, '(?ims)^\s*Variant:\s*debug\s*$.*?(?=^\s*Variant:|\z)')
    if (-not $debugBlock.Success) {
        throw 'Debug variant was not present in signingReport.'
    }
    $match = [regex]::Match($debugBlock.Value, '(?im)^\s*SHA1:\s*([0-9A-F]{2}(?::[0-9A-F]{2}){19})\s*$')
    if (-not $match.Success) {
        throw 'Debug variant did not contain a SHA-1 fingerprint.'
    }
    return $match.Groups[1].Value.ToUpperInvariant()
}

function Assert-CloudRunUrl {
    param([Parameter(Mandatory)][string]$Url)

    $parsed = $null
    if (-not [Uri]::TryCreate($Url, [UriKind]::Absolute, [ref]$parsed) -or
        $parsed.Scheme -cne 'https' -or
        $parsed.Host -notmatch '(^|\.)run\.app$' -or
        $Url -match '(?i)placeholder|example|unconfigured') {
        throw 'Service URL must be a non-placeholder HTTPS Cloud Run URL.'
    }
}

function Set-AndroidApiUrl {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Url)

    Assert-CloudRunUrl -Url $Url
    $lines = if (Test-Path -LiteralPath $Path -PathType Leaf) { @(Get-Content -LiteralPath $Path) } else { @() }
    $kept = @($lines | Where-Object { $_ -notmatch '^LEFTOVERS_API_BASE_URL=' })
    @($kept + "LEFTOVERS_API_BASE_URL=$($Url.TrimEnd('/'))/") | Set-Content -LiteralPath $Path -Encoding ASCII
}
