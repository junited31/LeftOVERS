$ErrorActionPreference = 'Stop'

$repositoryRoot = (& git rev-parse --show-toplevel).Trim()
$scannerSource = Join-Path $repositoryRoot 'scripts/scan_secrets.ps1'
$fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) 'leftovers-task-1-scanner-fixtures'
$locationPushed = $false

function Invoke-FixtureScanner {
    $output = @(& powershell -NoProfile -File $script:scannerCopy 2>&1)
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = $output
    }
}

function Assert-CleanScan([string] $scenario) {
    $result = Invoke-FixtureScanner
    if ($result.ExitCode -ne 0) {
        throw "$scenario expected exit 0, received $($result.ExitCode): $($result.Output -join [Environment]::NewLine)"
    }
    Write-Output "PASS clean: $scenario"
}

function Assert-RejectedScan([string] $scenario, [string] $category, [string] $relativePath) {
    $result = Invoke-FixtureScanner
    if ($result.ExitCode -eq 0) {
        throw "$scenario expected a nonzero exit."
    }

    $expectedFinding = "$category`: $relativePath"
    $renderedOutput = $result.Output -join [Environment]::NewLine
    if (-not $renderedOutput.Contains($expectedFinding)) {
        throw "$scenario did not report '$expectedFinding'."
    }
    Write-Output "PASS rejected: $scenario ($expectedFinding)"
}

if (Test-Path -LiteralPath $fixtureRoot) {
    Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
}

try {
    New-Item -ItemType Directory -Path (Join-Path $fixtureRoot 'scripts') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $fixtureRoot 'android/app') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $fixtureRoot 'fixtures') -Force | Out-Null
    & git init --quiet $fixtureRoot
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to initialize isolated fixture repository.'
    }

    $script:scannerCopy = Join-Path $fixtureRoot 'scripts/scan_secrets.ps1'
    Copy-Item -LiteralPath $scannerSource -Destination $script:scannerCopy

    Set-Content -LiteralPath (Join-Path $fixtureRoot 'task-1-red.txt') -Encoding Ascii -NoNewline -Value 'Literal task-1-red.txt language and a short sk- mention are documentation, not credentials.'
    $allowedGoogleValue = 'AI' + 'za' + ('A' * 35)
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'android/app/google-services.json') -Encoding Ascii -NoNewline -Value ("{`"current_key`":`"$allowedGoogleValue`"}")
    [System.IO.File]::WriteAllBytes((Join-Path $fixtureRoot 'fixtures/empty.bin'), [byte[]]@())
    [System.IO.File]::WriteAllBytes((Join-Path $fixtureRoot 'fixtures/malformed.bin'), [byte[]](0, 255, 195, 40, 128, 0))

    Push-Location $fixtureRoot
    $locationPushed = $true

    Assert-CleanScan 'task-1-red language, allowed Firebase config, empty file, and malformed binary'

    $openAiPath = 'fixtures/openai.txt'
    Set-Content -LiteralPath $openAiPath -Encoding Ascii -NoNewline -Value ('s' + 'k-' + ('A' * 20))
    Assert-RejectedScan 'OpenAI-shaped value' 'OpenAI key shape' $openAiPath
    Remove-Item -LiteralPath $openAiPath -Force

    $googlePath = 'fixtures/google.txt'
    Set-Content -LiteralPath $googlePath -Encoding Ascii -NoNewline -Value ('AI' + 'za' + ('B' * 35))
    Assert-RejectedScan 'Google-shaped value outside allowed path' 'Google API key shape' $googlePath
    Remove-Item -LiteralPath $googlePath -Force

    $pemPath = 'fixtures/private-key.txt'
    Set-Content -LiteralPath $pemPath -Encoding Ascii -NoNewline -Value ('-----BEGIN ' + 'PRIVATE KEY-----')
    Assert-RejectedScan 'PEM private-key header' 'PEM private-key header' $pemPath
    Remove-Item -LiteralPath $pemPath -Force

    Assert-CleanScan 'post-fixture cleanup'
}
finally {
    if ($locationPushed) {
        Pop-Location
    }
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}

if (Test-Path -LiteralPath $fixtureRoot) {
    throw "Fixture cleanup failed: $fixtureRoot"
}
Write-Output 'PASS cleanup: isolated fixture repository removed.'
