# LeftOVERS

LeftOVERS is a native Android app that turns the food already in a kitchen into three equipment-compatible recipe choices, guides cooking, and keeps pantry and meal history available offline. A minimal FastAPI service protects the OpenAI key, validates structured responses, and enforces fixed quotas.

- Repository: https://github.com/junited31/LeftOVERS
- Backend health: https://leftovers-api-rlm4ngxglq-du.a.run.app/health
- Submission readiness: [docs/submission.md](docs/submission.md)
- Demo plan: [docs/demo-script.md](docs/demo-script.md)
- Asset provenance: [docs/asset-provenance.md](docs/asset-provenance.md)
- Codex session: `019f706b-b713-7780-a456-f63ab18b173a`

## Product flow

1. Add pantry items and choose available equipment.
2. Request exactly three diverse recipes.
3. Start a recipe and optionally ask for structured photo guidance.
4. Confirm ingredient use, save feedback, and review the completed meal offline.

The app has no account UI, cloud backup, recipe marketplace, ads, or background photo upload. Firebase anonymous authentication protects transport to the backend; it is not a user account.

## Requirements

- Windows PowerShell, Git, and Java 17
- Android SDK 35 and Android build-tools 35.0.0
- Python 3.14
- `ffprobe` from FFmpeg on `PATH` for final demo-media verification
- Docker Desktop for the container gate
- A physical Android 10+ device for connected tests and the real-cloud flow

On Windows, install an FFmpeg distribution with `ffprobe`, add its `bin` directory to `PATH`, open a new PowerShell window, and confirm `ffprobe -version` succeeds before running the submission verifier.

## Build and install

<!-- instructions:build -->

From the repository root:

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
cd android
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:lintDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.junited31.leftovers/.MainActivity
```

The checked-in Firebase client configuration is restricted to package `com.junited31.leftovers`, the demo signing certificate, and the Identity Toolkit and Secure Token APIs. The OpenAI key is never placed in the APK.

## ADB-only sample data and reset

The public demo artifact is the debug APK because its sample-data controls are intentionally ADB-only. Launch the app once so the debug-only receiver is registered, then run:

```powershell
adb shell am broadcast -a com.junited31.leftovers.DEBUG_SEED
adb shell am force-stop com.junited31.leftovers
adb shell am start -n com.junited31.leftovers/.MainActivity
```

The seed replaces local state with rice, spinach, eggs, selected equipment, and an in-progress cooking session. Reset uses the same debug-only boundary:

```powershell
adb shell am broadcast -a com.junited31.leftovers.DEBUG_RESET
```

`DEBUG_SEED`, `DEBUG_RESET`, `DebugSeedReceiver`, and `DebugApplication` are absent from the release APK. The release artifact is evidence of that production boundary; it is not the judge-download artifact.

## Backend setup

```powershell
python -m venv backend\.venv
backend\.venv\Scripts\python.exe -m pip install -r backend\requirements.txt
backend\.venv\Scripts\python.exe -m uvicorn app.main:app --app-dir backend
Invoke-RestMethod http://127.0.0.1:8000/health
```

Production configuration uses `OPENAI_API_KEY` and `QUOTA_HASH_KEY` in Google Secret Manager. Firebase Admin uses Application Default Credentials. Do not put either secret in source, `local.properties`, an APK, or submission evidence.

## Tests

<!-- instructions:test -->

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:connectedDebugAndroidTest
cd ..
backend\.venv\Scripts\python.exe -m pytest -q backend\tests
docker build -t leftovers-api:test backend
powershell -NoProfile -File scripts\scan_secrets.ps1
python scripts\verify_submission.py
```

The final verifier command intentionally exits nonzero while the public release, checksum, SHA-bound `ffprobe` demo evidence, narrated YouTube demo, and submitted Devpost page are pending. It invokes a bounded real `ffprobe` process and compares the observed duration and audio-stream count with the evidence JSON and exact local media SHA-256. It also requires all GitHub artifact URLs to belong to this repository and the published checksum body to match the local debug APK. It passes only after those real public artifacts are reachable.

## AI and Codex

<!-- narrative:codex -->

Codex was used as an engineering collaborator for contract-first planning, RED-to-GREEN implementation, focused code review, real-device diagnosis, Cloud Run deployment, and evidence-backed verification. The current `/feedback` session is `019f706b-b713-7780-a456-f63ab18b173a`; the history is not represented as autonomous authorship or a substitute for testing.

<!-- narrative:gpt-5.6 -->

The backend uses the OpenAI Responses API with exact model alias `gpt-5.6`. Recipe generation requests Structured Outputs for three candidates, validates pantry bindings, equipment compatibility, uniqueness, cuisine/technique diversity, and retries only schema/diversity failures. Cooking-photo input also returns a typed observation/action/confidence/safety response. Every Responses API call sets `store=false`.

## Privacy, retention, and safety

Pantry, equipment, recipe snapshots, active sessions, feedback, retained final-dish photos, and history use local Android storage. There is no cloud copy of that product data.

<!-- disclosure:pantry -->

Only the selected pantry items needed for a recipe request leave the device for transient AI processing.

<!-- disclosure:equipment -->

The selected equipment list leaves the device with a recipe request so incompatible recipes can be rejected.

<!-- disclosure:preferences -->

The selected preference and recent-history signals leave the device with a recipe request to rank useful choices.

<!-- disclosure:cooking-photo -->

A cooking-step photo leaves the device only when the user explicitly captures or selects it for advice. Android deletes its managed temporary copy after success, typed failure, or cancellation; the backend does not save an application-owned copy. A separately selected final-dish photo remains only in app-private local storage.

<!-- disclosure:store-false -->

The backend forwards these inputs to OpenAI transiently with `store=false`, does not persist request bodies, and redacts tokens and bodies from logs. Firestore stores only HMAC-derived per-day quota-counter document IDs and counts.

Photo guidance cannot establish doneness, contamination, allergy safety, or food safety. The UI requires the cook to verify time, temperature, labels, and normal kitchen hygiene.

## Quotas and cost boundary

<!-- quota:installation-fairness -->

Anonymous Firebase per-UID daily limits provide installation-level fairness only. Reinstalling can create a new UID, so this is not described as strong abuse prevention.

<!-- quota:global-cost-boundary -->

The transactional global daily caps are the actual spend boundary: 2,000 recipe generations and 5,000 cooking-advice calls. Per-UID limits are 20 and 50 respectively, reset at UTC midnight, and a rejected over-limit request returns `429` with `Retry-After`.

## License

<!-- license:mit -->

Copyright (c) 2026 junited31. Released under the [MIT License](LICENSE).
