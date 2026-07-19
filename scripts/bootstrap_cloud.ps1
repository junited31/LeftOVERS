[CmdletBinding()]
param(
    [string]$ProjectId = 'leftovers-019f706b',
    [string]$PackageName = 'com.junited31.leftovers',
    [Parameter(Mandatory)][string]$FirebaseKeyId,
    [string]$GoogleServicesJson,
    [string]$OpenAiApiKeyFile,
    [string]$QuotaHashKeyFile,
    [string]$GcloudExecutable = 'gcloud',
    [string]$FirebaseExecutable = 'firebase',
    [string]$GradleExecutable,
    [switch]$Execute
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = @(& git rev-parse --show-toplevel 2>$null)[0]
if ([string]::IsNullOrWhiteSpace($repositoryRoot)) { throw 'Run this script inside the LeftOVERS Git worktree.' }
$repositoryRoot = $repositoryRoot.Trim()
. (Join-Path $PSScriptRoot 'cloud_contracts.ps1')

if ([string]::IsNullOrWhiteSpace($GoogleServicesJson)) {
    $GoogleServicesJson = Join-Path $repositoryRoot 'android\app\google-services.json'
}
if ([string]::IsNullOrWhiteSpace($GradleExecutable)) {
    $GradleExecutable = Join-Path $repositoryRoot 'android\gradlew.bat'
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

function Invoke-Status {
    param([Parameter(Mandatory)][string]$Executable, [Parameter(Mandatory)][string[]]$Arguments)

    if (-not (Get-Command $Executable -ErrorAction SilentlyContinue)) { throw "Required command is unavailable: $Executable" }
    $global:LASTEXITCODE = 0
    $output = @(& $Executable @Arguments 2>$null)
    return [pscustomobject]@{ Success = ($LASTEXITCODE -eq 0); Output = (($output -join "`n").Trim()) }
}

$activeProject = Invoke-Checked $GcloudExecutable @('config', 'get-value', 'project')
if ($activeProject.Trim() -cne $ProjectId) { throw "Active gcloud project must be $ProjectId; found '$($activeProject.Trim())'." }
$describedProject = Invoke-Checked $GcloudExecutable @('projects', 'describe', $ProjectId, '--format=value(projectId)')
if ($describedProject.Trim() -cne $ProjectId) { throw "Target project $ProjectId does not exist or is not accessible." }
$billing = Invoke-Checked $GcloudExecutable @('billing', 'projects', 'describe', $ProjectId, '--format=value(billingEnabled)')
Assert-BillingEnabled -Value $billing -ProjectId $ProjectId

$signingReport = Invoke-Checked $GradleExecutable @('-p', (Join-Path $repositoryRoot 'android'), ':app:signingReport', '--console=plain')
$sha1 = Get-DebugSigningSha1 -SigningReport $signingReport
$projectNumber = Invoke-Checked $GcloudExecutable @('projects', 'describe', $ProjectId, '--format=value(projectNumber)')
$openAiSecret = Invoke-Status $GcloudExecutable @('secrets', 'describe', 'OPENAI_API_KEY', "--project=$ProjectId", '--format=value(name)')
$quotaSecret = Invoke-Status $GcloudExecutable @('secrets', 'describe', 'QUOTA_HASH_KEY', "--project=$ProjectId", '--format=value(name)')
$openAiVersion = if ($openAiSecret.Success) { Invoke-Status $GcloudExecutable @('secrets', 'versions', 'list', 'OPENAI_API_KEY', '--filter=state=ENABLED', '--limit=1', "--project=$ProjectId", '--format=value(name)') } else { [pscustomobject]@{ Success = $false; Output = '' } }
$quotaVersion = if ($quotaSecret.Success) { Invoke-Status $GcloudExecutable @('secrets', 'versions', 'list', 'QUOTA_HASH_KEY', '--filter=state=ENABLED', '--limit=1', "--project=$ProjectId", '--format=value(name)') } else { [pscustomobject]@{ Success = $false; Output = '' } }
$openAiVersion.Success = $openAiVersion.Success -and -not [string]::IsNullOrWhiteSpace($openAiVersion.Output)
$quotaVersion.Success = $quotaVersion.Success -and -not [string]::IsNullOrWhiteSpace($quotaVersion.Output)
if ($Execute) {
    if (-not $openAiVersion.Success -and -not (Test-Path -LiteralPath $OpenAiApiKeyFile -PathType Leaf)) {
        throw 'OPENAI_API_KEY has no enabled version; supply -OpenAiApiKeyFile with a local secret file.'
    }
    if (-not $quotaVersion.Success -and -not (Test-Path -LiteralPath $QuotaHashKeyFile -PathType Leaf)) {
        throw 'QUOTA_HASH_KEY has no enabled version; supply -QuotaHashKeyFile with a local secret file.'
    }
    $services = @(
        'apikeys.googleapis.com', 'artifactregistry.googleapis.com', 'cloudbuild.googleapis.com',
        'firebase.googleapis.com', 'firestore.googleapis.com', 'identitytoolkit.googleapis.com',
        'run.googleapis.com', 'secretmanager.googleapis.com', 'securetoken.googleapis.com'
    )
    Invoke-Checked $GcloudExecutable (@('services', 'enable') + $services + @("--project=$ProjectId", '--quiet')) | Out-Null
}

$firebaseProjects = Invoke-Checked $FirebaseExecutable @('projects:list', '--json') | ConvertFrom-Json
$knownProjects = @($firebaseProjects.results) + @($firebaseProjects.result)
if (-not ($knownProjects | Where-Object { $_.projectId -ceq $ProjectId })) {
    if (-not $Execute) { throw "Firebase project $ProjectId is not registered." }
    Invoke-Checked $FirebaseExecutable @('projects:addfirebase', $ProjectId, '--non-interactive') | Out-Null
}

$firebaseApps = Invoke-Checked $FirebaseExecutable @('apps:list', 'ANDROID', '--project', $ProjectId, '--json') | ConvertFrom-Json
$app = @(@($firebaseApps.result) + @($firebaseApps.results) | Where-Object { $_.packageName -ceq $PackageName })[0]
if ($null -eq $app) {
    if (-not $Execute) { throw "Firebase Android app $PackageName is not registered." }
    Invoke-Checked $FirebaseExecutable @('apps:create', 'ANDROID', 'LeftOVERS', '--package-name', $PackageName, '--project', $ProjectId, '--non-interactive') | Out-Null
    $firebaseApps = Invoke-Checked $FirebaseExecutable @('apps:list', 'ANDROID', '--project', $ProjectId, '--json') | ConvertFrom-Json
    $app = @(@($firebaseApps.result) + @($firebaseApps.results) | Where-Object { $_.packageName -ceq $PackageName })[0]
}
if ($null -eq $app -or [string]::IsNullOrWhiteSpace($app.appId)) { throw "Firebase Android app $PackageName could not be resolved." }

if ($Execute) {
    $configDirectory = Split-Path -Parent $GoogleServicesJson
    if (-not (Test-Path -LiteralPath $configDirectory)) { New-Item -ItemType Directory -Path $configDirectory -Force | Out-Null }
    Invoke-Checked $FirebaseExecutable @('apps:sdkconfig', 'ANDROID', $app.appId, '--project', $ProjectId, '--out', $GoogleServicesJson) | Out-Null
    $shaList = Invoke-Checked $FirebaseExecutable @('apps:android:sha:list', $app.appId, '--project', $ProjectId, '--json')
    if ($shaList -notmatch [regex]::Escape($sha1)) {
        Invoke-Checked $FirebaseExecutable @('apps:android:sha:create', $app.appId, $sha1, '--project', $ProjectId, '--non-interactive') | Out-Null
    }
}
Assert-FirebaseConfig -Path $GoogleServicesJson -ProjectId $ProjectId -PackageName $PackageName

$accessToken = Invoke-Checked $GcloudExecutable @('auth', 'print-access-token')
$identityUri = "https://identitytoolkit.googleapis.com/admin/v2/projects/$ProjectId/config"
$headers = @{ Authorization = "Bearer $accessToken" }
$identityConfig = Invoke-RestMethod -Method Get -Uri $identityUri -Headers $headers
if (-not $identityConfig.signIn.anonymous.enabled) {
    if (-not $Execute) { throw 'Firebase Anonymous Authentication is not enabled.' }
    $identityConfig = Invoke-RestMethod -Method Patch -Uri "${identityUri}?updateMask=signIn.anonymous.enabled" -Headers $headers -ContentType 'application/json' -Body '{"signIn":{"anonymous":{"enabled":true}}}'
}
if (-not $identityConfig.signIn.anonymous.enabled) { throw 'Firebase Anonymous Authentication verification failed.' }
$accessToken = $null

$firestore = Invoke-Status $GcloudExecutable @('firestore', 'databases', 'describe', '--database=(default)', "--project=$ProjectId", '--format=value(name)')
if (-not $firestore.Success) {
    if (-not $Execute) { throw 'Firestore default database is missing.' }
    Invoke-Checked $GcloudExecutable @('firestore', 'databases', 'create', '--database=(default)', '--location=asia-northeast3', "--project=$ProjectId", '--quiet') | Out-Null
}

foreach ($secret in @(
    @{ Name = 'OPENAI_API_KEY'; Exists = $openAiSecret.Success; VersionExists = $openAiVersion.Success; File = $OpenAiApiKeyFile },
    @{ Name = 'QUOTA_HASH_KEY'; Exists = $quotaSecret.Success; VersionExists = $quotaVersion.Success; File = $QuotaHashKeyFile }
)) {
    if (-not $secret.Exists) {
        if (-not $Execute) { throw "Secret $($secret.Name) is missing." }
        Invoke-Checked $GcloudExecutable @('secrets', 'create', $secret.Name, '--replication-policy=automatic', "--project=$ProjectId", '--quiet') | Out-Null
    }
    if (-not $secret.VersionExists) {
        if (-not $Execute) { throw "Secret $($secret.Name) has no enabled version." }
        Invoke-Checked $GcloudExecutable @('secrets', 'versions', 'add', $secret.Name, "--data-file=$($secret.File)", "--project=$ProjectId", '--quiet') | Out-Null
    }
    if ($Execute) {
        Invoke-Checked $GcloudExecutable @('secrets', 'add-iam-policy-binding', $secret.Name, "--member=serviceAccount:$projectNumber-compute@developer.gserviceaccount.com", '--role=roles/secretmanager.secretAccessor', "--project=$ProjectId", '--quiet') | Out-Null
    }
}

$keyJson = [System.IO.Path]::GetTempFileName()
try {
    if ($Execute) {
        Invoke-Checked $GcloudExecutable @(
            'services', 'api-keys', 'update', $FirebaseKeyId,
            "--allowed-application=sha1_fingerprint=$sha1,package_name=$PackageName",
            '--api-target=service=identitytoolkit.googleapis.com', '--api-target=service=securetoken.googleapis.com',
            '--location=global', "--project=$ProjectId", '--quiet'
        ) | Out-Null
    }
    Invoke-Checked $GcloudExecutable @('services', 'api-keys', 'describe', $FirebaseKeyId, '--location=global', "--project=$ProjectId", '--format=json') |
        Set-Content -LiteralPath $keyJson -Encoding UTF8
    Assert-AndroidKeyRestrictions -Path $keyJson -PackageName $PackageName -Sha1 $sha1
}
finally {
    Remove-Item -LiteralPath $keyJson -Force -ErrorAction SilentlyContinue
}

Write-Output $(if ($Execute) { 'Cloud bootstrap completed and verified.' } else { 'Cloud bootstrap checks passed; no resources were changed.' })
