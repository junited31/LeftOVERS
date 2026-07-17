$ErrorActionPreference = 'Stop'

$repositoryRoot = (& git rev-parse --show-toplevel 2>$null)
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($repositoryRoot)) {
    Write-Error 'Secret scan must run inside a Git worktree.'
    exit 2
}

$repositoryRoot = $repositoryRoot.Trim()
$files = @(& git ls-files --cached --others --exclude-standard)
if ($LASTEXITCODE -ne 0) {
    Write-Error 'Unable to enumerate repository files for secret scanning.'
    exit 2
}

$openAiPattern = '(?<![A-Za-z0-9_])s' + 'k-[A-Za-z0-9_-]{20,}'
$googlePattern = 'AI' + 'za[A-Za-z0-9_-]{35}'
$pemPattern = '-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY(?: BLOCK)?-----'
$allowedGooglePath = 'android/app/google-services.json'
$findings = [System.Collections.Generic.List[string]]::new()

foreach ($relativePath in $files) {
    if ([string]::IsNullOrEmpty($relativePath)) {
        continue
    }

    $fullPath = Join-Path -Path $repositoryRoot -ChildPath $relativePath
    try {
        $bytes = [System.IO.File]::ReadAllBytes($fullPath)
    }
    catch {
        $findings.Add("Unreadable file: $relativePath")
        continue
    }

    if ($bytes.Length -eq 0) {
        continue
    }

    # Secret formats are ASCII. Decoding every byte this way also makes malformed
    # text and binary input safe to inspect without encoding-dependent failures.
    $content = [System.Text.Encoding]::ASCII.GetString($bytes)

    if ([regex]::IsMatch($content, $openAiPattern)) {
        $findings.Add("OpenAI key shape: $relativePath")
    }

    $normalizedPath = $relativePath.Replace('\', '/')
    if ($normalizedPath -cne $allowedGooglePath -and [regex]::IsMatch($content, $googlePattern)) {
        $findings.Add("Google API key shape: $relativePath")
    }

    if ([regex]::IsMatch($content, $pemPattern)) {
        $findings.Add("PEM private-key header: $relativePath")
    }
}

if ($findings.Count -gt 0) {
    Write-Error ("Secret scan failed:`n" + ($findings -join "`n"))
    exit 1
}

Write-Output "Secret scan passed ($($files.Count) files inspected)."
