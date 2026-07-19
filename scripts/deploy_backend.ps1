[CmdletBinding()]
param(
    [string]$ProjectId = 'leftovers-019f706b',
    [string]$Region = 'asia-northeast3',
    [string]$ServiceName = 'leftovers-api',
    [Parameter(Mandatory)][string]$FirebaseTokenFile,
    [Parameter(Mandatory)][string]$RecipeRequestJson,
    [Parameter(Mandatory)][string]$AdviceContextJson,
    [string]$AndroidPropertiesPath,
    [string]$GcloudExecutable = 'gcloud',
    [string]$CurlExecutable = 'curl.exe',
    [string]$PythonExecutable,
    [switch]$Execute
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = @(& git rev-parse --show-toplevel 2>$null)[0]
if ([string]::IsNullOrWhiteSpace($repositoryRoot)) { throw 'Run this script inside the LeftOVERS Git worktree.' }
$repositoryRoot = $repositoryRoot.Trim()
. (Join-Path $PSScriptRoot 'cloud_contracts.ps1')
if ([string]::IsNullOrWhiteSpace($AndroidPropertiesPath)) {
    $AndroidPropertiesPath = Join-Path $repositoryRoot 'android\local.properties'
}
if ([string]::IsNullOrWhiteSpace($PythonExecutable)) {
    $venvPython = Join-Path $repositoryRoot 'backend\.venv\Scripts\python.exe'
    $PythonExecutable = if (Test-Path -LiteralPath $venvPython -PathType Leaf) { $venvPython } else { 'python' }
}

function Invoke-Checked {
    param([Parameter(Mandatory)][string]$Executable, [Parameter(Mandatory)][string[]]$Arguments)

    if (-not (Get-Command $Executable -ErrorAction SilentlyContinue)) { throw "Required command is unavailable: $Executable" }
    $stderr = [System.IO.Path]::GetTempFileName()
    try {
        $global:LASTEXITCODE = 0
        $output = @(& $Executable @Arguments 2>$stderr)
        if ($LASTEXITCODE -ne 0) { throw "Command failed: $Executable $($Arguments[0])" }
        return ($output -join "`n").Trim()
    }
    finally {
        Remove-Item -LiteralPath $stderr -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-Preflight {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $output = @(Invoke-Checked $CurlExecutable $Arguments)
    $lines = @(($output -join "`n") -split "`r?`n")
    if ($lines.Count -lt 2) { throw 'Preflight did not return an HTTP status.' }
    return [pscustomobject]@{
        Status = [int]$lines[-1]
        Body = ($lines[0..($lines.Count - 2)] -join "`n")
    }
}

function Assert-ResponseSchema {
    param([Parameter(Mandatory)][string]$Body, [Parameter(Mandatory)][ValidateSet('RecipeGenerateResponse', 'CookingAdviceResponse')][string]$Schema)

    if (-not (Get-Command $PythonExecutable -ErrorAction SilentlyContinue)) { throw "Required command is unavailable: $PythonExecutable" }
    $responseFile = [System.IO.Path]::GetTempFileName()
    try {
        [System.IO.File]::WriteAllText($responseFile, $Body, [System.Text.UTF8Encoding]::new($false))
        $validation = "import pathlib,sys; from app.models import $Schema; $Schema.model_validate_json(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))"
        Push-Location (Join-Path $repositoryRoot 'backend')
        try {
            $pythonError = [System.IO.Path]::GetTempFileName()
            $previousErrorAction = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            $global:LASTEXITCODE = 0
            & $PythonExecutable -c $validation $responseFile 2>$pythonError
            $validationExitCode = $LASTEXITCODE
            $ErrorActionPreference = $previousErrorAction
            Remove-Item -LiteralPath $pythonError -Force -ErrorAction SilentlyContinue
            if ($validationExitCode -ne 0) { throw "$Schema schema validation failed." }
        }
        finally {
            $ErrorActionPreference = 'Stop'
            Pop-Location
        }
    }
    finally {
        Remove-Item -LiteralPath $responseFile -Force -ErrorAction SilentlyContinue
    }
}

foreach ($path in @($FirebaseTokenFile, $RecipeRequestJson, $AdviceContextJson)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required preflight file is missing: $path" }
}
if (-not $Execute) { throw 'Deployment changes require the explicit -Execute switch.' }

$activeProject = Invoke-Checked $GcloudExecutable @('config', 'get-value', 'project')
if ($activeProject.Trim() -cne $ProjectId) { throw "Active gcloud project must be $ProjectId; found '$($activeProject.Trim())'." }
$describedProject = Invoke-Checked $GcloudExecutable @('projects', 'describe', $ProjectId, '--format=value(projectId)')
if ($describedProject.Trim() -cne $ProjectId) { throw "Target project $ProjectId does not exist or is not accessible." }
$billing = Invoke-Checked $GcloudExecutable @('billing', 'projects', 'describe', $ProjectId, '--format=value(billingEnabled)')
Assert-BillingEnabled -Value $billing -ProjectId $ProjectId

Invoke-Checked $GcloudExecutable @(
    'run', 'deploy', $ServiceName,
    "--source=$(Join-Path $repositoryRoot 'backend')",
    "--region=$Region", "--project=$ProjectId",
    '--allow-unauthenticated',
    '--set-secrets=OPENAI_API_KEY=OPENAI_API_KEY:latest,QUOTA_HASH_KEY=QUOTA_HASH_KEY:latest',
    '--quiet'
) | Out-Null
$serviceUrl = Invoke-Checked $GcloudExecutable @(
    'run', 'services', 'describe', $ServiceName,
    "--region=$Region", "--project=$ProjectId", '--format=value(status.url)'
)
Assert-CloudRunUrl -Url $serviceUrl

$baseUrl = $serviceUrl.TrimEnd('/')
$authConfig = [System.IO.Path]::GetTempFileName()
$invalidAuthConfig = [System.IO.Path]::GetTempFileName()
$syntheticPng = [System.IO.Path]::GetTempFileName()
try {
    $token = (Get-Content -Raw -LiteralPath $FirebaseTokenFile).Trim()
    if ([string]::IsNullOrWhiteSpace($token)) { throw 'Firebase token file is empty.' }
    Set-Content -LiteralPath $authConfig -Value "header = `"Authorization: Bearer $token`"" -Encoding ASCII
    Set-Content -LiteralPath $invalidAuthConfig -Value 'header = "Authorization: Bearer invalid"' -Encoding ASCII
    [System.IO.File]::WriteAllBytes($syntheticPng, [Convert]::FromBase64String('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2nEAAAAAASUVORK5CYII='))

    $health = Invoke-Preflight @('--silent', '--show-error', '--output', '-', '--write-out', "`n%{http_code}", "$baseUrl/health")
    if ($health.Status -ne 200 -or (ConvertFrom-Json $health.Body).status -cne 'ok') { throw 'Health preflight failed.' }

    $unauthorized = Invoke-Preflight @('--silent', '--show-error', '--output', '-', '--write-out', "`n%{http_code}", '--config', $invalidAuthConfig, '--header', 'Content-Type: application/json', '--data-binary', "@$RecipeRequestJson", "$baseUrl/v1/recipes/generate")
    if ($unauthorized.Status -ne 401) { throw 'Invalid-token preflight must return 401.' }

    $recipes = Invoke-Preflight @('--silent', '--show-error', '--output', '-', '--write-out', "`n%{http_code}", '--config', $authConfig, '--header', 'Content-Type: application/json', '--data-binary', "@$RecipeRequestJson", "$baseUrl/v1/recipes/generate")
    if ($recipes.Status -ne 200) { throw 'Structured recipe preflight failed.' }
    Assert-ResponseSchema -Body $recipes.Body -Schema 'RecipeGenerateResponse'

    $advice = Invoke-Preflight @('--silent', '--show-error', '--output', '-', '--write-out', "`n%{http_code}", '--config', $authConfig, '--form', "context=<$AdviceContextJson", '--form', "photo=@$syntheticPng;type=image/png", "$baseUrl/v1/cooking/advice")
    if ($advice.Status -ne 200) { throw 'Synthetic PNG advice preflight failed.' }
    Assert-ResponseSchema -Body $advice.Body -Schema 'CookingAdviceResponse'
    Set-AndroidApiUrl -Path $AndroidPropertiesPath -Url $serviceUrl
    Write-Output "Deployment and health/auth/text/image preflights passed for $serviceUrl"
}
finally {
    Remove-Item -LiteralPath $authConfig, $invalidAuthConfig, $syntheticPng -Force -ErrorAction SilentlyContinue
}
