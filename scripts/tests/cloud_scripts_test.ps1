$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$fixtures = Join-Path $PSScriptRoot 'fixtures'
$bootstrap = Join-Path $repositoryRoot 'scripts\bootstrap_cloud.ps1'
$deploy = Join-Path $repositoryRoot 'scripts\deploy_backend.ps1'
$contracts = Join-Path $repositoryRoot 'scripts\cloud_contracts.ps1'
$expectedSha1 = 'AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD'
$global:cloudTestCalls = [System.Collections.Generic.List[string]]::new()
$global:cloudTestActiveProject = 'leftovers-019f706b'
$global:cloudTestBilling = 'true'
$global:cloudTestKeyFixture = Join-Path $fixtures 'key-restrictions.valid.json'
$global:cloudTestFirebaseProjectExists = $true
$global:cloudTestFirebaseAppExists = $true
$global:cloudTestFirestoreExists = $true
$global:cloudTestSecretsExist = $true
$global:cloudTestSecretVersionsExist = $true
$global:cloudTestAnonymousEnabled = $true
$global:cloudTestShaExists = $true
$global:cloudTestInvalidRecipe = $false
$global:cloudTestProjectId = 'leftovers-019f706b'
$global:cloudTestProjectDescribeFails = $false
$global:cloudTestStatusWritesError = $false
$global:cloudTestCheckedWritesError = $false
$global:cloudTestResourceKey = 'fixture-key'

function global:gcloud_fixture {
    $command = $args -join ' '
    $global:cloudTestCalls.Add($command)
    if ($command -like 'config get-value project*') { $global:cloudTestActiveProject; return }
    if ($command -like 'projects describe*') {
        if ($global:cloudTestProjectDescribeFails) { $global:LASTEXITCODE = 1; return }
        if ($command -like '*projectNumber*') { '1234567890'; return }
        $global:cloudTestProjectId
        return
    }
    if ($command -like 'billing projects describe*') { $global:cloudTestBilling; return }
    if ($command -like 'services api-keys get-key-string*') { $global:cloudTestResourceKey; return }
    if ($command -like 'services api-keys describe*') { Get-Content -Raw $global:cloudTestKeyFixture; return }
    if ($command -like 'services api-keys update*') { return }
    if ($command -like 'secrets describe*') {
        if ($global:cloudTestSecretsExist) { 'existing'; return }
        if ($global:cloudTestStatusWritesError) { Write-Error 'fixture missing secret' }
        $global:LASTEXITCODE = 1
        return
    }
    if ($command -like 'secrets versions list*') {
        if ($global:cloudTestSecretVersionsExist) { 'projects/fixture/secrets/fixture/versions/1'; return }
        return
    }
    if ($command -like 'firestore databases describe*') {
        if ($global:cloudTestFirestoreExists) { '(default)'; return }
        $global:LASTEXITCODE = 1
        return
    }
    if ($command -like 'services enable*') {
        if ($global:cloudTestCheckedWritesError) { Write-Error 'fixture progress' }
        return
    }
    if ($command -like 'firestore databases create*') { $global:cloudTestFirestoreExists = $true; return }
    if ($command -like 'secrets create*') { return }
    if ($command -like 'secrets versions add*') { return }
    if ($command -like 'secrets add-iam-policy-binding*') { return }
    if ($command -like 'auth print-access-token*') { 'fixture-access-token'; return }
    if ($command -like 'run deploy*') {
        if ($global:cloudTestCheckedWritesError) { Write-Error 'fixture progress' }
        return
    }
    if ($command -like 'run services describe*') { 'https://leftovers-api-abc123-an.a.run.app'; return }
    throw "Unexpected gcloud fixture command: $command"
}

function global:firebase_fixture {
    $command = $args -join ' '
    $global:cloudTestCalls.Add("firebase $command")
    if ($command -like 'projects:list*') {
        if ($global:cloudTestFirebaseProjectExists) { '{"results":[{"projectId":"leftovers-019f706b"}]}' } else { '{"results":[]}' }
        return
    }
    if ($command -like 'projects:addfirebase*') { $global:cloudTestFirebaseProjectExists = $true; return }
    if ($command -like 'apps:list*') {
        if ($global:cloudTestFirebaseAppExists) {
            '{"result":[{"appId":"1:fixture:android:fixture","platform":"ANDROID","packageName":"com.junited31.leftovers"}]}'
        } else {
            '{"result":[]}'
        }
        return
    }
    if ($command -like 'apps:create*') { $global:cloudTestFirebaseAppExists = $true; return }
    if ($command -like 'apps:sdkconfig*') {
        $outIndex = [Array]::IndexOf($args, '--out')
        if ($outIndex -lt 0) { throw 'apps:sdkconfig fixture requires --out.' }
        $source = (Resolve-Path (Join-Path $fixtures 'google-services.valid.json')).Path
        $destination = [System.IO.Path]::GetFullPath($args[$outIndex + 1])
        if ($source -cne $destination) { Copy-Item -LiteralPath $source -Destination $destination }
        return
    }
    if ($command -like 'apps:android:sha:list*') {
        if ($global:cloudTestShaExists) { "{`"certHashes`":[$(ConvertTo-Json $expectedSha1 -Compress)]}" } else { '{"certHashes":[]}' }
        return
    }
    if ($command -like 'apps:android:sha:create*') { $global:cloudTestShaExists = $true; return }
    throw "Unexpected firebase fixture command: $command"
}

function global:Invoke-RestMethod {
    param($Method, $Uri, $Headers, $Body, $ContentType)
    $global:cloudTestCalls.Add("rest $Method $Uri")
    Assert-True ($Headers['X-Goog-User-Project'] -ceq 'leftovers-019f706b') 'Identity Toolkit requests require the target quota project header.'
    if ($Method -eq 'Patch') { $global:cloudTestAnonymousEnabled = $true }
    return [pscustomobject]@{ signIn = [pscustomobject]@{ anonymous = [pscustomobject]@{ enabled = $global:cloudTestAnonymousEnabled } } }
}

function global:gradle_fixture {
    $global:cloudTestCalls.Add("gradle $($args -join ' ')")
    Get-Content (Join-Path $fixtures 'signing-report.txt')
}

function global:curl_fixture {
    $command = $args -join ' '
    $global:cloudTestCalls.Add("curl $command")
    $invalidAuth = $false
    for ($index = 0; $index -lt $args.Count - 1; $index++) {
        if ($args[$index] -eq '--config' -and (Get-Content -Raw -LiteralPath $args[$index + 1]) -match 'Bearer invalid') {
            $invalidAuth = $true
        }
    }
    if ($command -match '/health') { '{"status":"ok"}'; '200'; return }
    if ($command -match '/v1/recipes/generate' -and $invalidAuth) {
        '{"error":{"code":"unauthorized","message":"Valid Firebase bearer token required"}}'; '401'; return
    }
    if ($command -match '/v1/recipes/generate') {
        if ($global:cloudTestInvalidRecipe) {
            Get-Content -Raw (Join-Path $fixtures 'recipe-response.invalid.json')
        } else {
            Get-Content -Raw (Join-Path $fixtures 'recipe-response.valid.json')
        }
        '200'
        return
    }
    if ($command -match '/v1/cooking/advice') {
        $photoForm = @($args | Where-Object { $_ -like 'photo=@*;type=image/png' })[0]
        $photoPath = $photoForm.Substring('photo=@'.Length).Split(';')[0]
        Add-Type -AssemblyName System.Drawing
        $image = [System.Drawing.Image]::FromFile($photoPath)
        try {
            Assert-True ($image.Width -ge 256 -and $image.Height -ge 256) 'Synthetic image preflight must exercise a model-usable PNG.'
        }
        finally {
            $image.Dispose()
        }
        Get-Content -Raw (Join-Path $fixtures 'advice-response.valid.json'); '200'; return
    }
    throw "Unexpected curl fixture command: $command"
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Invoke-Bootstrap {
    & $bootstrap `
        -FirebaseKeyId 'fixture-key-id' `
        -GoogleServicesJson (Join-Path $fixtures 'google-services.valid.json') `
        -GcloudExecutable 'gcloud_fixture' `
        -FirebaseExecutable 'firebase_fixture' `
        -GradleExecutable 'gradle_fixture' `
        @args
}

function Expect-Failure([scriptblock]$Action, [string]$Pattern) {
    try {
        & $Action
    }
    catch {
        Assert-True ($_.Exception.Message -match $Pattern) "Expected failure matching '$Pattern', got '$($_.Exception.Message)'. $($_.ScriptStackTrace)"
        return
    }
    throw "Expected failure matching '$Pattern'."
}

function Expect-ExactFailure([scriptblock]$Action, [string]$Message) {
    try {
        & $Action
    }
    catch {
        Assert-True ($_.Exception.Message -ceq $Message) "Expected exact failure '$Message', got '$($_.Exception.Message)'."
        return
    }
    throw "Expected exact failure '$Message'."
}

. $contracts

# Given binary secret bytes, when bootstrap input is checked, then Cloud Run-incompatible text is rejected.
$invalidSecret = Join-Path ([System.IO.Path]::GetTempPath()) "leftovers-secret-$([guid]::NewGuid()).bin"
try {
    [System.IO.File]::WriteAllBytes($invalidSecret, [byte[]]@(0xC3, 0x28))
    Expect-Failure { Assert-Utf8SecretFile -Path $invalidSecret } 'valid nonblank UTF-8'
    Assert-Utf8SecretFile -Path (Join-Path $fixtures 'token.txt')
}
finally {
    Remove-Item -LiteralPath $invalidSecret -Force -ErrorAction SilentlyContinue
}

# Given a different active project, when bootstrap starts, then no target command is attempted.
$global:cloudTestCalls.Clear()
$global:cloudTestActiveProject = 'unrelated-project'
Expect-Failure { Invoke-Bootstrap } 'Active gcloud project must be leftovers-019f706b'
Assert-True ($global:cloudTestCalls.Count -eq 1) 'Active-project mismatch must stop immediately.'

# Given disabled billing, when execute is requested, then it stops before every billable command.
$global:cloudTestCalls.Clear()
$global:cloudTestActiveProject = 'leftovers-019f706b'
$global:cloudTestBilling = "`r`n FALSE `n"
Expect-ExactFailure { Invoke-Bootstrap -Execute } 'gcloud billing projects link leftovers-019f706b --billing-account=<CALLER_SUPPLIED_ID>'
Assert-True (-not (($global:cloudTestCalls -join "`n") -match 'services enable|projects:addfirebase|apps:create|rest Patch|run deploy|secrets create|secrets versions add|secrets add-iam-policy-binding|firestore databases create|api-keys update')) 'A mutation command ran before billing was true.'

# Given mixed-case true with whitespace, when execute is requested, then billing precedes all writes.
$global:cloudTestCalls.Clear()
$global:cloudTestBilling = " `r`n TrUe `n "
$global:cloudTestCheckedWritesError = $true
Invoke-Bootstrap -Execute
$global:cloudTestCheckedWritesError = $false
$billingIndex = $global:cloudTestCalls.FindIndex({ param($call) $call -like 'billing projects describe*' })
$writeIndex = $global:cloudTestCalls.FindIndex({ param($call) $call -like 'services enable*' })
Assert-True ($billingIndex -ge 0 -and $writeIndex -gt $billingIndex) 'Billing must be checked before the first write.'
$secretProbeIndex = $global:cloudTestCalls.FindIndex({ param($call) $call -like 'secrets describe*' })
Assert-True ($writeIndex -lt $secretProbeIndex) 'Required APIs must be enabled before Secret Manager is probed.'
Assert-True (-not (($global:cloudTestCalls -join "`n") -match 'firebase apps:sdkconfig')) 'A verified existing Firebase config must not be downloaded again.'

# Given a caller-selected API key that is not google-services current_key, bootstrap must fail before restricting it.
$global:cloudTestCalls.Clear()
$global:cloudTestResourceKey = 'different-fixture-key'
Expect-Failure { Invoke-Bootstrap -Execute } 'FirebaseKeyId must identify google-services.json current_key'
Assert-True (-not (($global:cloudTestCalls -join "`n") -match 'services api-keys update')) 'A non-binding API key must never be restricted as the Android app key.'
$global:cloudTestResourceKey = 'fixture-key'

# Given no Firebase/Firestore/secrets/auth resources, when execute runs after billing, then every mutation is idempotently provisioned behind the gate.
$provisionedConfig = Join-Path ([System.IO.Path]::GetTempPath()) "google-services-$([guid]::NewGuid()).json"
try {
    $global:cloudTestCalls.Clear()
    $global:cloudTestFirebaseProjectExists = $false
    $global:cloudTestFirebaseAppExists = $false
    $global:cloudTestFirestoreExists = $false
    $global:cloudTestSecretsExist = $false
    $global:cloudTestSecretVersionsExist = $false
    $global:cloudTestStatusWritesError = $true
    $global:cloudTestAnonymousEnabled = $false
    $global:cloudTestShaExists = $false
    & $bootstrap `
        -FirebaseKeyId 'fixture-key-id' `
        -GoogleServicesJson $provisionedConfig `
        -OpenAiApiKeyFile (Join-Path $fixtures 'token.txt') `
        -QuotaHashKeyFile (Join-Path $fixtures 'token.txt') `
        -GcloudExecutable 'gcloud_fixture' `
        -FirebaseExecutable 'firebase_fixture' `
        -GradleExecutable 'gradle_fixture' `
        -Execute
    $billingIndex = $global:cloudTestCalls.FindIndex({ param($call) $call -like 'billing projects describe*' })
    foreach ($pattern in @('services enable*', 'firebase projects:addfirebase*', 'firebase apps:create*', 'firebase apps:android:sha:create*', 'rest Patch*', 'firestore databases create*', 'secrets create*', 'secrets versions add*', 'secrets add-iam-policy-binding*', 'services api-keys update*')) {
        $mutationIndex = $global:cloudTestCalls.FindIndex({ param($call) $call -like $pattern })
        Assert-True ($mutationIndex -gt $billingIndex) "Mutation '$pattern' did not run after billing."
    }
    Assert-FirebaseConfig -Path $provisionedConfig -ProjectId 'leftovers-019f706b' -PackageName 'com.junited31.leftovers'
}
finally {
    Remove-Item -LiteralPath $provisionedConfig -Force -ErrorAction SilentlyContinue
    $global:cloudTestFirebaseProjectExists = $true
    $global:cloudTestFirebaseAppExists = $true
    $global:cloudTestFirestoreExists = $true
    $global:cloudTestSecretsExist = $true
    $global:cloudTestSecretVersionsExist = $true
    $global:cloudTestStatusWritesError = $false
    $global:cloudTestAnonymousEnabled = $true
    $global:cloudTestShaExists = $true
}

# Given existing secrets without enabled versions, when execute reruns, then it adds versions without recreating secrets.
$global:cloudTestCalls.Clear()
$global:cloudTestSecretVersionsExist = $false
Invoke-Bootstrap -Execute -OpenAiApiKeyFile (Join-Path $fixtures 'token.txt') -QuotaHashKeyFile (Join-Path $fixtures 'token.txt')
Assert-True (($global:cloudTestCalls -join "`n") -match 'secrets versions add') 'Missing secret versions were not repaired.'
Assert-True (-not (($global:cloudTestCalls -join "`n") -match 'secrets create')) 'Existing secrets must not be recreated.'
$global:cloudTestSecretVersionsExist = $true

# Given Firebase and signing fixtures, when contracts are checked, then exact values pass.
Assert-FirebaseConfig -Path (Join-Path $fixtures 'google-services.valid.json') -ProjectId 'leftovers-019f706b' -PackageName 'com.junited31.leftovers'
Assert-AndroidKeyRestrictions -Path (Join-Path $fixtures 'key-restrictions.valid.json') -PackageName 'com.junited31.leftovers' -Sha1 $expectedSha1
$compactKey = Join-Path ([System.IO.Path]::GetTempPath()) "key-restrictions-$([guid]::NewGuid()).json"
try {
    $compact = Get-Content -Raw (Join-Path $fixtures 'key-restrictions.valid.json') | ConvertFrom-Json
    $compact.restrictions.androidKeyRestrictions.allowedApplications[0].sha1Fingerprint = $expectedSha1.Replace(':', '')
    $compact | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $compactKey -Encoding UTF8
    Assert-AndroidKeyRestrictions -Path $compactKey -PackageName 'com.junited31.leftovers' -Sha1 $expectedSha1
}
finally {
    Remove-Item -LiteralPath $compactKey -Force -ErrorAction SilentlyContinue
}
Assert-True ((Get-DebugSigningSha1 -SigningReport (Get-Content -Raw (Join-Path $fixtures 'signing-report.txt'))) -eq $expectedSha1) 'Signing SHA-1 extraction failed.'
Assert-True ((Get-DebugSigningSha1 -SigningReport (Get-Content -Raw (Join-Path $fixtures 'signing-report-multi.txt'))) -eq $expectedSha1) 'Signing SHA-1 must come from the debug variant block.'
Expect-Failure {
    Get-DebugSigningSha1 -SigningReport (Get-Content -Raw (Join-Path $fixtures 'signing-report-no-debug.txt'))
} 'debug variant'

# Given one extra API, when restrictions are checked, then the contract fails closed.
Expect-Failure {
    Assert-AndroidKeyRestrictions -Path (Join-Path $fixtures 'key-restrictions.extra-api.json') -PackageName 'com.junited31.leftovers' -Sha1 $expectedSha1
} 'API restrictions must equal'

# Given deployment URLs, when validated, then only a real HTTPS Cloud Run URL passes.
Assert-CloudRunUrl -Url 'https://leftovers-api-abc123-an.a.run.app'
Expect-Failure { Assert-CloudRunUrl -Url 'http://127.0.0.1:8080' } 'HTTPS Cloud Run URL'
Expect-Failure { Assert-CloudRunUrl -Url 'https://example.com' } 'HTTPS Cloud Run URL'

# Given a real Cloud Run URL, when it is injected, then existing local properties survive and one URL is written.
$properties = Join-Path ([System.IO.Path]::GetTempPath()) "leftovers-$([guid]::NewGuid()).properties"
try {
    Set-Content -LiteralPath $properties -Value 'sdk.dir=C:\Android\Sdk' -Encoding ASCII
    Set-AndroidApiUrl -Path $properties -Url 'https://leftovers-api-abc123-an.a.run.app'
    $propertyText = Get-Content -Raw -LiteralPath $properties
    Assert-True ($propertyText -match 'sdk\.dir=') 'Existing Android properties were not preserved.'
    Assert-True (([regex]::Matches($propertyText, '(?m)^LEFTOVERS_API_BASE_URL=')).Count -eq 1) 'Exactly one API URL property is required.'
}
finally {
    Remove-Item -LiteralPath $properties -Force -ErrorAction SilentlyContinue
}

# Given a three-item but schema-invalid model response, when deploy preflights it, then backend Pydantic validation fails.
$properties = Join-Path ([System.IO.Path]::GetTempPath()) "leftovers-$([guid]::NewGuid()).properties"
try {
    $global:cloudTestInvalidRecipe = $true
    Expect-Failure {
        & $deploy `
            -FirebaseTokenFile (Join-Path $fixtures 'token.txt') `
            -RecipeRequestJson (Join-Path $fixtures 'recipe-request.json') `
            -AdviceContextJson (Join-Path $fixtures 'advice-context.json') `
            -AndroidPropertiesPath $properties `
            -GcloudExecutable 'gcloud_fixture' `
            -CurlExecutable 'curl_fixture' `
            -Execute
    } 'schema validation failed'
    Assert-True (-not (Test-Path -LiteralPath $properties)) 'A failed preflight must not inject the service URL.'
}
finally {
    $global:cloudTestInvalidRecipe = $false
    Remove-Item -LiteralPath $properties -Force -ErrorAction SilentlyContinue
}

# Given a project-describe mismatch, when deploy starts, then it stops before billing, deploy, preflights, or injection.
$properties = Join-Path ([System.IO.Path]::GetTempPath()) "leftovers-$([guid]::NewGuid()).properties"
try {
    $global:cloudTestCalls.Clear()
    $global:cloudTestProjectId = 'unrelated-project'
    Expect-Failure {
        & $deploy `
            -FirebaseTokenFile (Join-Path $fixtures 'token.txt') `
            -RecipeRequestJson (Join-Path $fixtures 'recipe-request.json') `
            -AdviceContextJson (Join-Path $fixtures 'advice-context.json') `
            -AndroidPropertiesPath $properties `
            -GcloudExecutable 'gcloud_fixture' `
            -CurlExecutable 'curl_fixture' `
            -Execute
    } 'Target project leftovers-019f706b'
    Assert-True (-not (($global:cloudTestCalls -join "`n") -match 'billing projects describe|run deploy|curl ')) 'Project mismatch reached a later deploy stage.'
    Assert-True (-not (Test-Path -LiteralPath $properties)) 'Project mismatch injected a URL.'
}
finally {
    $global:cloudTestProjectId = 'leftovers-019f706b'
    Remove-Item -LiteralPath $properties -Force -ErrorAction SilentlyContinue
}

# Given project-describe failure, when deploy starts, then it fails closed before all mutations and preflights.
$properties = Join-Path ([System.IO.Path]::GetTempPath()) "leftovers-$([guid]::NewGuid()).properties"
try {
    $global:cloudTestCalls.Clear()
    $global:cloudTestProjectDescribeFails = $true
    Expect-Failure {
        & $deploy `
            -FirebaseTokenFile (Join-Path $fixtures 'token.txt') `
            -RecipeRequestJson (Join-Path $fixtures 'recipe-request.json') `
            -AdviceContextJson (Join-Path $fixtures 'advice-context.json') `
            -AndroidPropertiesPath $properties `
            -GcloudExecutable 'gcloud_fixture' `
            -CurlExecutable 'curl_fixture' `
            -Execute
    } 'Command failed: gcloud_fixture projects'
    Assert-True (-not (($global:cloudTestCalls -join "`n") -match 'billing projects describe|run deploy|curl ')) 'Project describe failure reached a later deploy stage.'
    Assert-True (-not (Test-Path -LiteralPath $properties)) 'Project describe failure injected a URL.'
}
finally {
    $global:cloudTestProjectDescribeFails = $false
    Remove-Item -LiteralPath $properties -Force -ErrorAction SilentlyContinue
}

# Given billing is true and fixture HTTP responses, when deploy runs, then deploy precedes all four preflights.
$properties = Join-Path ([System.IO.Path]::GetTempPath()) "leftovers-$([guid]::NewGuid()).properties"
try {
    $global:cloudTestCalls.Clear()
    $global:cloudTestBilling = " True `r`n"
    $global:cloudTestCheckedWritesError = $true
    & $deploy `
        -FirebaseTokenFile (Join-Path $fixtures 'token.txt') `
        -RecipeRequestJson (Join-Path $fixtures 'recipe-request.json') `
        -AdviceContextJson (Join-Path $fixtures 'advice-context.json') `
        -AndroidPropertiesPath $properties `
        -GcloudExecutable 'gcloud_fixture' `
        -CurlExecutable 'curl_fixture' `
        -Execute
    $global:cloudTestCheckedWritesError = $false
    $deployIndex = $global:cloudTestCalls.FindIndex({ param($call) $call -like 'run deploy*' })
    $billingIndex = $global:cloudTestCalls.FindIndex({ param($call) $call -like 'billing projects describe*' })
    Assert-True ($deployIndex -gt $billingIndex) 'Deploy must run only after billing is true.'
    Assert-True ((@($global:cloudTestCalls | Where-Object { $_ -like 'curl *' })).Count -eq 4) 'Health, auth, text, and image preflights are required.'
    Assert-True ((Get-Content -Raw $properties) -match '^LEFTOVERS_API_BASE_URL=https://.*\.run\.app/?' ) 'Cloud Run URL was not injected.'
}
finally {
    $global:cloudTestCheckedWritesError = $false
    Remove-Item -LiteralPath $properties -Force -ErrorAction SilentlyContinue
}

# Given a fresh Firebase project, when bootstrap is inspected, then Authentication initialization is present.
$bootstrapSource = Get-Content -Raw -LiteralPath $bootstrap
Assert-True ($bootstrapSource -match 'identityPlatform:initializeAuth') 'Fresh projects must initialize Firebase Authentication before reading its config.'

# Given Android source, when the deployment contract is inspected, then BuildConfig owns the injected URL.
$buildScript = Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot 'android\app\build.gradle.kts')
$applicationSource = Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot 'android\app\src\main\java\com\junited31\leftovers\LeftoversApplication.kt')
Assert-True ($buildScript -match 'LEFTOVERS_API_BASE_URL') 'Android build does not consume the injected API URL.'
Assert-True ($applicationSource -match 'BuildConfig\.LEFTOVERS_API_BASE_URL') 'Android runtime still uses a hard-coded API URL.'

# Given the canonical Google config path, when the scanner runs, then only that AIza-shaped value is allowed.
$googleConfig = Join-Path $repositoryRoot 'android\app\google-services.json'
$forbidden = Join-Path $repositoryRoot 'scripts\tests\forbidden-secret.tmp'
$createdGoogleConfig = -not (Test-Path -LiteralPath $googleConfig)
try {
    if ($createdGoogleConfig) {
        $syntheticGoogleKey = 'AI' + 'za' + ('A' * 35)
        Set-Content -LiteralPath $googleConfig -Value ("{`"current_key`":`"$syntheticGoogleKey`"}") -Encoding ASCII
    }
    & (Join-Path $repositoryRoot 'scripts\scan_secrets.ps1') | Out-Null
    Set-Content -LiteralPath $forbidden -Value ('AI' + 'za' + ('B' * 35)) -Encoding ASCII
    Expect-Failure { & (Join-Path $repositoryRoot 'scripts\scan_secrets.ps1') 2>&1 | Out-Null } 'Google API key shape'
}
finally {
    Remove-Item -LiteralPath $forbidden -Force -ErrorAction SilentlyContinue
    if ($createdGoogleConfig) { Remove-Item -LiteralPath $googleConfig -Force -ErrorAction SilentlyContinue }
}

Write-Output 'Cloud script contract tests passed.'
