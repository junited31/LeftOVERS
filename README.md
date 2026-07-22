# LeftOVERS

LeftOVERS is a native Android app that turns the food already in a kitchen into three equipment-compatible recipe choices, guides cooking, and keeps pantry and meal history available offline. A minimal FastAPI service calls Vertex AI Gemini with Application Default Credentials (ADC), validates structured responses, and enforces fixed quotas.

- Repository: https://github.com/junited31/LeftOVERS
- Backend health: https://leftovers-api-rlm4ngxglq-du.a.run.app/health
- Submission readiness: [docs/submission.md](docs/submission.md)
- Demo plan: [docs/demo-script.md](docs/demo-script.md)
- Asset provenance: [docs/asset-provenance.md](docs/asset-provenance.md)
- Privacy and data controls: [docs/privacy.md](docs/privacy.md)
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

The checked-in Firebase client configuration is restricted to package `com.junited31.leftovers`, the demo signing certificate, and the Identity Toolkit and Secure Token APIs. Vertex credentials and backend secrets are never placed in the APK.

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

Production uses the Cloud Run service account through Google ADC for Vertex AI and Firebase Admin. `QUOTA_HASH_KEY` remains in Google Secret Manager. The legacy OpenAI implementation and secret are retained for historical/parity truth but are not mounted into the Vertex production revision. Do not put credentials or secrets in source, `local.properties`, an APK, or submission evidence.

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

The final verifier command intentionally exits nonzero while the public release, checksum, SHA-bound `ffprobe` demo evidence, narrated YouTube demo, and submitted Devpost page are pending. It validates strict schema-v2 records bound to one publication source commit, invokes a bounded real `ffprobe` process, and compares observed media facts with exact bytes. Expansion capture is separately checked with the strict 11-state visual/artifact command documented in [docs/submission.md](docs/submission.md).

## AI and Codex

<!-- narrative:codex -->

Codex was used as an engineering collaborator for contract-first planning, RED-to-GREEN implementation, focused code review, real-device diagnosis, Cloud Run deployment, and evidence-backed verification. The current `/feedback` session is `019f706b-b713-7780-a456-f63ab18b173a`; the history is not represented as autonomous authorship or a substitute for testing.

<!-- narrative:gpt-5.6 -->

The current runtime uses fixed Vertex AI Gemini routes through ADC: recipe generation starts with `gemini-3.1-flash-lite` and has one bounded best-effort `gemini-3.5-flash` fallback for eligible transient failures; cooking-photo advice reverses those roles. The backend still requires exactly three schema-valid candidates and typed photo advice. The earlier GPT-5.6/OpenAI Responses API implementation materially contributed the original validated flow and remains in the repository for historical/parity context, including its `store=false` calls; it is not the current production route.

## Privacy, retention, and safety

Pantry, equipment, recipe snapshots, active sessions, feedback, retained final-dish photos, and history use local Android storage. LeftOVERS does not create a cloud backup of that product data. See [the full privacy and provider-control disclosure](docs/privacy.md).

<!-- disclosure:pantry -->

Only the selected pantry items needed for a recipe request leave the device for transient AI processing.

<!-- disclosure:equipment -->

The selected equipment list leaves the device with a recipe request so incompatible recipes can be rejected.

<!-- disclosure:preferences -->

The selected preference and recent-history signals leave the device with a recipe request to rank useful choices.

<!-- disclosure:cooking-photo -->

A cooking-step photo leaves the device only when the user explicitly captures or selects it for advice. Android deletes its managed temporary copy after success, typed failure, or cancellation; the backend does not save an application-owned copy. A separately selected final-dish photo remains only in app-private local storage.

<!-- disclosure:store-false -->

The backend sends the selected request context to Vertex AI for transient inference, keeps cooking-photo bytes in memory only for the request, does not create an application-owned server copy, and logs metadata rather than request bodies or image bytes. Firestore stores only HMAC-derived per-day quota document IDs and counters. Provider-side handling remains governed by Google Cloud terms and controls; this project does not claim Zero Data Retention. OpenAI `store=false` appears only in the retained legacy path and is not a Vertex setting, a data-sharing opt-out, a training control, or by itself a Zero Data Retention guarantee. See the current official [Google managed-model data governance](https://docs.cloud.google.com/gemini-enterprise-agent-platform/resources/zero-data-retention), [Google ADC](https://docs.cloud.google.com/docs/authentication/application-default-credentials), and [OpenAI API data controls](https://developers.openai.com/api/docs/guides/your-data).

Photo guidance cannot establish doneness, contamination, allergy safety, or food safety. The UI requires the cook to verify time, temperature, labels, and normal kitchen hygiene.

Vertex inference is billable and availability/fallback is best effort; quotas bound application calls but do not promise model availability or a particular bill. No optional synthetic-data sharing project is enabled. If synthetic sharing is evaluated later, it must use a separately isolated project and only generated, non-user inputs after explicit approval.

## Quotas and cost boundary

<!-- quota:installation-fairness -->

Anonymous Firebase per-UID daily limits provide installation-level fairness only. Reinstalling can create a new UID, so this is not described as strong abuse prevention.

<!-- quota:global-cost-boundary -->

The transactional global daily caps are the actual spend boundary: 2,000 recipe generations and 5,000 cooking-advice calls. Per-UID limits are 20 and 50 respectively, reset at UTC midnight, and a rejected over-limit request returns `429` with `Retry-After`.

## License

<!-- license:mit -->

Copyright (c) 2026 junited31. Released under the [MIT License](LICENSE).
