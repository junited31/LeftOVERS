# LeftOVERS - Work Plan

## TL;DR (For humans)
**What you'll get:** A native Android app that remembers pantry quantities and kitchen equipment, suggests three varied recipes that use expiring ingredients, coaches the cook from a photo, updates leftovers after the meal, learns from feedback, and keeps an offline cooking history. It will include a protected GPT-5.6 service, a public testable APK, and judge-ready documentation and demo materials.

**Why this approach:** The retained source of truth stays on the phone for privacy and reliable offline access; only the selected pantry, equipment, preference context, or cooking photo is sent transiently for AI processing. A small authenticated service protects the OpenAI key and enforces quotas, while deterministic validation and ranking keep ingredient use, equipment compatibility, variety, and feedback testable.

**What it will NOT do:** The first release will not add iOS or web clients, social/shopping/nutrition features, cloud sync, custom appliance management, recipe crawling, or automatic food-safety claims. It will not upload data in the background or retain cooking photos on the server.

**Effort:** XL
**Risk:** High - the deadline requires a native app, deployed multimodal AI service, real-device proof, public APK, and complete contest submission in one coordinated build.
**Decisions to sanity-check:** Keep pantry units strict instead of converting them automatically; keep sign-in invisible and local data unsynced; use fixed equipment choices; ship Android only; apply fixed daily AI quotas with no judge bypass.

Your next move: start implementation from this dual-reviewed approved plan. Full execution detail follows below.

---

> TL;DR (machine): XL/high-risk Android Kotlin + Room app, authenticated FastAPI GPT-5.6 text/image service, deterministic recipe/feedback loop, real-device/cloud verification, and public Devpost delivery artifacts.

## Scope
### Must have
- Public MIT monorepo containing an Android native app in `android/` and a secret-holding FastAPI service in `backend/`.
- Local pantry quantities (`g`, `ml`, `count`), expiry dates, fixed equipment checklist, generated recipe snapshots, active cooking session, meal logs, preferences, and retained final-photo paths; amounts use signed 64-bit integer milli-units and UI input allows at most three decimals.
- Exactly three GPT-5.6 recipe candidates with hard equipment filtering, deterministic ingredient/expiry/preference/novelty ranking, one exact diversity predicate, pantry-row binding, and missing-ingredient disclosure.
- Step-by-step cooking UI with native camera/gallery still-photo input and typed GPT-5.6 photo advice that never asserts food safety from vision.
- Atomic completion that validates actual use, preserves remaining quantities, saves rating/measurement adjustments/notes/recommend-again, and feeds later ranking.
- History timeline/detail, offline read paths, typed API failures, debug-only seed/reset, real-device E2E, deployed backend, public APK, and complete English submission materials.
### Must NOT have (guardrails, anti-slop, scope boundaries)
- No iOS/web/tablet-specific app, social sharing, shopping list, nutrition/health tracking, receipt/barcode scanning, voice, smart-appliance control, payments, ads, or notifications.
- No visible account, cloud backup/sync, Firestore mirror of Room data, server-side photo/history storage, provider-retained Responses (`store` must be false), recipe crawler/marketplace, copyrighted recipe corpus, or ML recommendation pipeline.
- No CameraX/custom preview, custom equipment CRUD, implicit unit conversion, background AI retries/photo re-upload, multi-module clean architecture, one-implementation interfaces, factories, or speculative plugin/config frameworks.
- Debug seed/reset code must not be reachable or packaged in release. Secrets/service-account files must never enter Git, logs, APK resources, evidence, or submission text.

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: TDD. Android domain/data uses JUnit 4 + coroutines-test + Room in-memory tests; UI uses Compose test + AndroidJUnitRunner; backend uses pytest + FastAPI TestClient/httpx with injected Firebase/OpenAI fakes. Every behavioral todo captures RED before production code and GREEN after.
- Evidence root outside ulw-loop: `.omo/evidence/leftovers/`; use `task-<N>-red.txt`, `task-<N>-green.txt`, and the named `.json`, `.png`, `.xml`, or `.mp4` surface artifact. Under ulw-loop, replace the root with its current attempt directory.
- Android full gate: `cd android && ./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:connectedDebugAndroidTest` and parse XML totals under `android/app/build/test-results/` plus `android/app/build/outputs/androidTest-results/connected/`.
- Backend full gate: `backend/.venv/Scripts/python.exe -m pytest -q backend/tests` plus `docker build -t leftovers-api:test backend`.
- Real surface: install the APK on connected `SM_F711N`, run the focused cloud E2E instrumentation, launch `com.junited31.leftovers/.MainActivity`, capture `adb exec-out screencap -p`, and verify deployed HTTP responses with `curl -i`.
- Failure matrix: offline, malformed schema, invalid/expired token, 413, 422, quota 429/concurrency, upstream 502, cancellation, process restart, stale inventory version, unit mismatch, over-consumption, cache sweep, and release-build debug-hook absence.
- Secret gate: every todo reuses `powershell -NoProfile -File scripts/scan_secrets.ps1`; it excludes only `android/app/google-services.json` from long `AIza` detection, scans every other tracked path for actual OpenAI-key/Google-key/PEM shapes, and never uses the self-matching broad pattern `sk-`.

## Execution strategy
### Parallel execution waves
> Target 5-8 todos per wave. Fewer than 3 (except the final) means you under-split.
- Wave 1 foundation (3 todos): T1 repository/build skeleton, T2 Android domain/persistence contracts, T3 backend/auth/quota/OpenAI contracts. T2 and T3 run in parallel after T1.
- Wave 2 product loop (6 todos, staged rather than fully parallel): T4 pantry/equipment and T5 Android network/photo lifecycle in parallel, then T6 -> T7 -> T8 -> T9 serially.
- Wave 3 hardening/delivery (3 todos): T10 offline/accessibility/release hardening, T11 isolated Firebase/Cloud Run deployment, T12 public APK/demo/submission artifacts.
- Final wave: F1-F4 run independently after T12 and all must return unconditional approval.

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| T1 | none | T2,T3 | none |
| T2 | T1 | T4,T6,T7,T8,T9 | T3 |
| T3 | T1 | T5,T6,T7,T11 | T2 |
| T4 | T2 | T6,T8,T10 | T5 |
| T5 | T3 | T6,T7,T10 | T4 |
| T6 | T2,T3,T4,T5 | T7,T8,T10 | none |
| T7 | T2,T3,T5,T6 | T8,T10 | none |
| T8 | T2,T4,T6,T7 | T9,T10 | none |
| T9 | T2,T8 | T10 | none |
| T10 | T4-T9 | T11,T12 | none |
| T11 | T3,T10 | T12 | none |
| T12 | T10,T11 | F1-F4 | none |

## Todos
> Implementation + Test = ONE todo. Never separate.
- [x] 1. Scaffold the reproducible monorepo and build contracts
  What to do / Must NOT do: Create root `README.md`, `LICENSE`, `.gitignore`, `android/`, `backend/`, and `scripts/scan_secrets.ps1`; use Gradle 8.10.2, AGP 8.7.3, Kotlin/Compose plugin 2.0.21, Java 17, compile/target 35, minSdk 29, package `com.junited31.leftovers`, and Python package pins from the approved draft. Generate the wrapper and one Compose `MainActivity`; create FastAPI `GET /health`. The one PowerShell secret scanner enumerates `git ls-files --cached --others --exclude-standard`, rejects actual `(?<![A-Za-z0-9_])sk-[A-Za-z0-9_-]{20,}`, long `AIza...`, and PEM shapes, and excludes only `android/app/google-services.json` from the `AIza` rule. Add no feature architecture or secret values.
  Parallelization: Wave 1 | Blocked by: none | Blocks: T2,T3
  References: `.omo/drafts/leftovers.md:29-51,54,75`; known-good build files, verified build-tools, and canonical scanner decision are cited there.
  Acceptance criteria: capture RED from absent `android/gradlew.bat`, `backend/app/main.py`, and secret scanner; GREEN from repository root with `$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }; $aapt = Join-Path $sdk 'build-tools\35.0.0\aapt.exe'; if (-not (Test-Path -LiteralPath $aapt)) { throw 'Android build-tools 35.0.0 aapt.exe missing' }`, `cd android && ./gradlew.bat :app:assembleDebug :app:lintDebug`, then in a fresh shell `cd backend && .venv/Scripts/python.exe -c "from app.main import app; assert app.title == 'LeftOVERS API'"`; the aapt assertion exits nonzero when run with a temporary nonexistent `ANDROID_HOME`. `powershell -NoProfile -File scripts/scan_secrets.ps1` exits 0 while temporary fixtures prove `task-1-red.txt` and a Firebase config key do not self-fail, but real OpenAI/Google key shapes outside the one allowed Firebase config path and PEM headers do fail.
  QA scenarios: happy - `adb install -r android/app/build/outputs/apk/debug/app-debug.apk && adb shell am start -n com.junited31.leftovers/.MainActivity`, PASS when UIAutomator contains `LeftOVERS`, evidence `.omo/evidence/leftovers/task-1-home.xml`; failure - build a release variant and inspect `aapt dump badging`, PASS when package/minSdk/targetSdk mismatch would fail the scripted assertion, evidence `task-1-build-contract.txt`.
  Commit: Y | `build(scaffold): bootstrap Android and API projects`

- [x] 2. Define canonical domain models, Room schema, and atomic inventory completion
  What to do / Must NOT do: Add `PantryItemEntity`, `RecipeSnapshotEntity`, `CookSessionEntity`, `MealLogEntity`, DAOs, `LeftoversDatabase`, JSON converters, and DataStore keys. Pantry units are only `g|ml|count`; quantities are `Long` milli-units, UI parsing accepts at most three decimals, and rows carry an integer version. Recipe snapshots bind usage by pantry UUID/source version/unit. Implement one Room transaction that re-reads rows, validates unique known IDs, unit/version/`0 <= actualUse <= quantity`, writes the immutable meal log snapshot, and subtracts usage. Never clamp, auto-convert, deduct untracked missing ingredients, or delete zero rows.
  Parallelization: Wave 1 | Blocked by: T1 | Blocks: T4,T6,T7,T8,T9 | Can parallelize with: T3
  References: `.omo/drafts/leftovers.md:16-21,32,55-60,62,66-71`; numeric completion rules under Decisions.
  Acceptance criteria: RED first for over-use, unit mismatch, stale version, and rollback; GREEN via `cd android && ./gradlew.bat :app:testDebugUnitTest --tests '*CompleteCookSessionTest' --tests '*LeftoversDatabaseTest'`; assert restart persistence and full rollback on every invalid row.
  QA scenarios: happy - instrumentation inserts two pantry rows, completes a session, restarts the activity, and asserts exact remaining quantities plus one log; failure - two coroutines complete against one version and exactly one succeeds while the other returns `StaleInventory`, evidence `task-2-inventory-transaction.xml`.
  Commit: Y | `feat(data): add local kitchen and cooking records`

- [x] 3. Implement typed FastAPI contracts, Firebase auth, quotas, and GPT-5.6 adapters
  What to do / Must NOT do: Add `backend/app/{main,config,models,auth,quota,openai_client,prompts}.py`, Firestore-backed atomic counters, Pydantic request/response schemas, injected fakes, and endpoints `/health`, `/v1/recipes/generate`, `/v1/cooking/advice`. Recipe pantry rows contain UUID ID, integer version, name, canonical unit, positive `quantityMilliUnits`, and optional expiry; tracked uses echo unique known ID/version/unit with positive `proposedMilliUnits <= quantity`, while missing ingredients are separate and never deductible. Enforce 256 KiB JSON, 8 MiB photo, quotas 20/50 UID and 2000/5000 global per UTC day, HMAC-SHA256 UID doc IDs, typed 401/413/422/429/502, `store=false`, one initial model attempt plus at most two schema/diversity retries, bounded upload reads, close in `finally`, and redacted logs. Per-UID counters are installation fairness only; global counters are the cost boundary. Keep one global counter document per route/day; do not shard unless the concurrency test measures contention. Do not persist request content or accept missing auth.
  Parallelization: Wave 1 | Blocked by: T1 | Blocks: T5,T6,T7,T11 | Can parallelize with: T2
  References: `.omo/drafts/leftovers.md:34,41-51,61-76`; official model, quota, retry, scanner, and cloud decisions are cited there.
  Acceptance criteria: RED first for invalid token, oversize, concurrent quota, invalid schema, duplicate/unknown pantry IDs, version/unit/amount mismatch, duplicate recipes, upload close, `store=false`, and log redaction; GREEN with `backend/.venv/Scripts/python.exe -m pytest -q backend/tests` and `docker build -t leftovers-api:test backend`. Tests prove atomic 21st/51st rejection and global cap, UTC Retry-After, no model call on boundary failures, exactly two validation retries before typed 422, success when either retry repairs the set, and no response can bind an unrequested pantry row.
  QA scenarios: happy - `curl -i http://127.0.0.1:8000/health` returns 200 JSON and authenticated fake recipe/advice requests match schemas, evidence `task-3-api.json`; failure - 8 MiB+1 and expired token requests return 413/401 with zero stored bytes/model calls, evidence `task-3-boundaries.txt`.
  Commit: Y | `feat(api): add authenticated GPT cooking contracts`

- [x] 4. Build equipment onboarding and pantry CRUD
  What to do / Must NOT do: Implement Compose navigation shell, fixed equipment checklist, pantry list/add/edit forms, canonical units, optional expiry, validation, and Room/DataStore persistence. Include induction, gas burner, microwave, oven, air fryer, blender, rice cooker, toaster, basic cookware. Add debug-source-set-only seed/reset broadcast; no custom equipment CRUD or release receiver.
  Parallelization: Wave 2 | Blocked by: T2 | Blocks: T6,T8,T10 | Can parallelize with: T5
  References: `.omo/drafts/leftovers.md:16-18,31,35-38,49,65-68,73-76`.
  Acceptance criteria: RED Compose tests for empty name, non-positive or over-three-decimal quantity, equipment persistence, edit/restart, and release receiver absence; GREEN with `cd android && ./gradlew.bat :app:testDebugUnitTest :app:assembleRelease :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.junited31.leftovers.ui.PantryEquipmentTest`, then in that shell `$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }; & (Join-Path $sdk 'build-tools\35.0.0\aapt.exe') dump xmltree app/build/outputs/apk/release/app-release-unsigned.apk AndroidManifest.xml` showing no debug receiver.
  QA scenarios: happy - seed by `adb shell am broadcast -a com.junited31.leftovers.DEBUG_SEED`, restart, and capture pantry/equipment screens, evidence `task-4-pantry.png` and `task-4-equipment.png`; failure - submit blank/zero input and PASS when inline error appears and DB count is unchanged, evidence `task-4-validation.xml`.
  Commit: Y | `feat(pantry): track ingredients and kitchen equipment`

- [x] 5. Add Android auth/API client and ephemeral photo lifecycle
  What to do / Must NOT do: Add Firebase anonymous token provider, OkHttp client, typed error mapping, native `PickVisualMedia` + `TakePicture`, app `FileProvider`, 1280px/quality-80 JPEG compression, cache `finally` deletion, cancellation handling, and startup sweep older than 24h. Do not embed OpenAI secrets, retry automatically, upload in background, or use CameraX.
  Parallelization: Wave 2 | Blocked by: T3 | Blocks: T6,T7,T10 | Can parallelize with: T4
  References: `.omo/drafts/leftovers.md:32-34,45,56-57,61,64,70-76` plus photo/offline decisions.
  Acceptance criteria: RED for success/401/413/422/429/502/cancel/process-restart cache cleanup; GREEN via `cd android && ./gradlew.bat :app:testDebugUnitTest --tests '*LeftoversApiTest' --tests '*PhotoLifecycleTest' :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.junited31.leftovers.photo.PhotoPickerTest`. APK string scan contains no `OPENAI_API_KEY` or secret pattern.
  QA scenarios: happy - MockWebServer receives <=8 MiB JPEG and cache directory is empty after response, evidence `task-5-photo-lifecycle.json`; failure - cancel a delayed request and restart app, PASS when no network retry occurs and stale cache is swept, evidence `task-5-cancel.txt`.
  Commit: Y | `feat(network): connect secure AI and photo transport`

- [x] 6. Generate, validate, rank, and display three diverse recipes
  What to do / Must NOT do: Implement backend recipe prompt/schema, Android recipe request/response, deterministic normalizer/fingerprint/profile/ranker, candidate cards, scoring explanations, missing ingredients, and snapshot save. The prompt includes one compact valid three-candidate example. Hard-filter equipment/cooldowns; require exactly three unique normalized titles/fingerprints, at least two normalized cuisines, and at least two normalized primary techniques. Novelty compares normalized ingredient-name sets, never hash strings. Use the approved 0.45/0.25/0.20/0.10 algorithm/tie-breaks and strict pantry-row binding. No partial-set UI, crawler, recipe corpus, ML, or server persistence.
  Parallelization: Wave 2 | Blocked by: T2,T3,T4,T5 | Blocks: T7,T8,T10
  References: `.omo/drafts/leftovers.md:18,34-35,58-59,63,67-76`; exact ranking/fingerprint/retry definitions are cited there.
  Acceptance criteria: RED fixtures for missing equipment, three-vs-two candidates, duplicate title/fingerprint, only-one-cuisine, only-one-technique, unknown/duplicate pantry ID, unit/version/amount mismatch, no history, exact tag-score normalization, ingredient-set Jaccard, 20-log truncation, 30-day boundary, stable ties, expiry weights, and absence of the compact valid prompt example; GREEN with Android `*RecommendationRankerTest` and backend `test_recipe_generation.py`. Same fixture must produce byte-stable ordered IDs across three runs.
  QA scenarios: happy - authenticated seeded pantry request returns exactly three candidates and the app screenshot exposes usage/equipment/rank reasons, evidence `task-6-recipes.json` and `task-6-recipes.png`; failure - backend fake returns an invalid/duplicate set on the initial attempt and both retries, PASS on typed 422 and unchanged Room snapshot count, evidence `task-6-diversity-failure.txt`.
  Commit: Y | `feat(recipes): recommend diverse pantry-first meals`

- [x] 7. Implement step-by-step cooking and safe photo coaching
  What to do / Must NOT do: Add active-session resume, step navigation/timers as display-only durations, camera/gallery attachment, cooking-advice request, structured advice UI, confidence, and mandatory safety note. Preserve active steps offline. Never claim doneness/safety from the image or silently move to the next step.
  Parallelization: Wave 2 | Blocked by: T2,T3,T5,T6 | Blocks: T8,T10
  References: `.omo/drafts/leftovers.md:19,56-57,61-64,70-76`.
  Acceptance criteria: RED for resume after process recreation, safety-note omission, malformed response, offline retry UI, cancellation cleanup, and no automatic step advance; GREEN with Android `*CookingSessionTest`, connected `CookingPhotoFlowTest`, and backend `test_cooking_advice.py`.
  QA scenarios: happy - upload the generated/licensed cooking fixture at step 2 and `curl`/UI both show observations, actions, confidence, safety note, evidence `task-7-cooking-advice.json` and `task-7-cooking.png`; failure - network disabled with `adb shell cmd connectivity airplane-mode enable`, PASS when steps remain usable, retry is explicit, no DB mutation/re-upload occurs, then restore connectivity in cleanup receipt.
  Commit: Y | `feat(cooking): guide steps with photo advice`

- [ ] 8. Complete meals, retain leftovers, and learn deterministic preferences
  What to do / Must NOT do: Build actual-use editor, rating 1-5, ingredient measurement adjustments, notes, recommend-again toggle, optional final-photo retention, atomic completion call, profile derivation, exact fingerprint cooldown, and result confirmation. Keep adjustments as normalized ingredient name, preferred amount in milli-units, canonical unit, note, and completion timestamp; newest wins per name+unit, and the latest 20 unique keys become the next recipe request's `measurementHints`. Do not add a conversion engine or ML.
  Parallelization: Wave 2 | Blocked by: T2,T4,T6,T7 | Blocks: T9,T10
  References: `.omo/drafts/leftovers.md:20,55-60,66-68,72-76` plus unit/ranking/cooldown definitions.
  Acceptance criteria: RED for actual use edits, zero remaining rows, over-use/unit/stale rollback, exact preference tag-score formula, positive/negative recommendation, exact 30-day expiry, newest-wins adjustment precedence, 20-key truncation, next-request `measurementHints`, and final-photo path; GREEN with `*CompleteMealFlowTest`, `*PreferenceProfileTest`, and connected completion test.
  QA scenarios: happy - complete seeded recipe and assert exact inventory, one immutable log, retained photo, and next ranking boost, evidence `task-8-feedback.json`; failure - change pantry in a competing transaction before completion, PASS when UI reports stale inventory and no row/log/photo mutation occurs, evidence `task-8-stale.txt`.
  Commit: Y | `feat(feedback): update leftovers and future ranking`

- [ ] 9. Present persistent cooking history and details
  What to do / Must NOT do: Implement reverse-chronological history timeline and detail with final photo, recipe snapshot, actual use, remaining-after values, rating, adjustments, notes, and recommend-again state. Ensure app restart/offline access and debug-reset cascade cleanup. Do not add sharing, analytics, or cloud sync.
  Parallelization: Wave 2 | Blocked by: T2,T8 | Blocks: T10
  References: `.omo/drafts/leftovers.md:21,32,55-56,61,65,69-76`.
  Acceptance criteria: RED for empty state, ordering ties by UUID, restart/offline detail, missing photo fallback, reset cascade; GREEN with `*HistoryRepositoryTest` and connected `HistoryScreenTest`.
  QA scenarios: happy - seed two completed meals, restart offline, open newest detail, capture timeline/detail, evidence `task-9-history.png`; failure - delete the referenced test photo outside the app, PASS when fallback renders without crash or DB rewrite, evidence `task-9-missing-photo.xml`.
  Commit: Y | `feat(history): show completed cooking records`

- [ ] 10. Harden full Android behavior, accessibility, offline states, and release boundaries
  What to do / Must NOT do: Add content descriptions/semantics, short-screen scrolling, state restoration, typed error copy, debug-vs-release source-set checks, database migration/version tests, dependency/security scan, and one focused E2E with fake backend. Do not broaden product scope or add generic design-system/framework layers.
  Parallelization: Wave 3 | Blocked by: T4-T9 | Blocks: T11,T12
  References: all draft components/scope; `.omo/drafts/leftovers.md:16-21,66-92`.
  Acceptance criteria: full Android gate exits 0 with XML totals and zero lint errors; `cd android && ./gradlew.bat :app:dependencies`, `powershell -NoProfile -File scripts/scan_secrets.ps1`, and release debug-hook scan pass; connected E2E covers pantry->recipe->cook->feedback->history on `SM_F711N` including a short viewport and offline read.
  QA scenarios: happy - run focused `LeftoversFlowE2eTest` and capture final history screen, evidence `task-10-e2e.png`; failure - inject 401/413/422/429/502 sequentially and PASS when typed copy/retry policy matches and DB/photo cache hashes remain unchanged, evidence `task-10-errors.json`.
  Commit: Y | `fix(app): harden end-to-end cooking flow`

- [ ] 11. Provision isolated Firebase/Cloud Run and prove real GPT-5.6 traffic
  What to do / Must NOT do: Add idempotent `scripts/bootstrap_cloud.ps1` and `scripts/deploy_backend.ps1`; create/select project `leftovers-019f706b`, then before enabling APIs run `gcloud billing projects describe leftovers-019f706b --format='value(billingEnabled)'` and require trimmed output equal to `true` case-insensitively. If false, stop with the exact remediation `gcloud billing projects link leftovers-019f706b --billing-account=<CALLER_SUPPLIED_ID>`; never choose or link a billing account automatically. After the confirmed precondition, register package, enable anonymous auth/Firestore/required APIs, create `QUOTA_HASH_KEY` and `OPENAI_API_KEY` secrets, deploy `leftovers-api` to `asia-northeast3`, inject URL into Android BuildConfig, and obtain the demo APK certificate SHA-1 from `cd android && ./gradlew.bat :app:signingReport`. Restrict the Firebase key to Android package `com.junited31.leftovers` plus that SHA-1 and an API allowlist exactly equal to `identitytoolkit.googleapis.com` and `securetoken.googleapis.com`; any extra service fails. Run text-structured plus synthetic-PNG image preflights. Never touch unrelated Firebase projects or print secrets/tokens.
  Parallelization: Wave 3 | Blocked by: T3,T10 | Blocks: T12
  References: `.omo/drafts/leftovers.md:33-34,41-51,61,64,69-76,86`; cloud/model/billing/key decisions are cited there.
  Acceptance criteria: scripts are idempotent and stop if active project differs or trimmed billing output is not case-insensitive `true` before any billable API/deploy action; billing-disabled fixture exits nonzero and prints only the caller-supplied link-command template. `curl -i https://<service>/health` returns 200; invalid token 401; authenticated exact `gpt-5.6` structured/image smoke returns valid schema. Firebase config project/package match and live key inspection proves the application restriction contains exactly `com.junited31.leftovers` plus the demo APK SHA-1 and the API restriction set equals `{identitytoolkit.googleapis.com, securetoken.googleapis.com}` with no extras; a fixture adding `generativelanguage.googleapis.com` must fail. `powershell -NoProfile -File scripts/scan_secrets.ps1` allows that config and rejects any copied `AIza` value elsewhere. Firestore shows only HMAC quota docs; `docker build` and backend tests pass. External billing link/create/deploy actions require action-time confirmation.
  QA scenarios: happy - focused connected `RealCloudE2eTest` produces three recipes and photo advice against deployed service, evidence `task-11-cloud.json`; failure - expired token and concurrent quota probes return 401/one atomic 429 without model overrun, evidence `task-11-quota.txt`. Cleanup receipt removes only temporary local tokens/files, not the requested deployment.
  Commit: Y | `ops(cloud): deploy protected LeftOVERS API`

- [ ] 12. Publish judge-ready APK, documentation, demo, and submission evidence
  What to do / Must NOT do: Complete English `README.md` with setup/sample/test/debug-APK/backend instructions, Codex decision narrative, GPT-5.6 usage, explicit disclosure that selected pantry, equipment, preferences, and cooking photos leave the device for transient AI processing with `store=false`, privacy/safety, current session ID `019f706b-b713-7780-a456-f63ab18b173a`; describe anonymous per-UID quotas as installation fairness and the global caps as the actual cost boundary, without claiming strong abuse prevention. Add `docs/submission.md`, `docs/demo-script.md`, screenshots, generated/licensed fixture provenance, and `scripts/verify_submission.py`. Attach `android/app/build/outputs/apk/debug/app-debug.apk` to public GitHub release `v0.1.0-demo`; document its ADB-only debug seed/reset flow, while separately proving the release APK contains no such receiver. Record <180s device demo, add audio narration, upload Public to YouTube through authenticated browser after action-time confirmation, and replace every URL field with real reachable values. After the local verifier passes, use the authenticated browser to create/populate the `LeftOVERS` Devpost entry in Apps for Your Life with its description, technologies, repository, release/test instructions, public video, screenshots, Codex/GPT-5.6 explanation, and `/feedback` session ID. Request action-time confirmation immediately before the irreversible final Submit action; do not submit a draft with missing or placeholder fields.
  Parallelization: Wave 3 | Blocked by: T10,T11 | Blocks: F1-F4
  References: official rules/model links in `.omo/drafts/leftovers.md:41-42`; approved judge delivery and submission scope in `.omo/drafts/leftovers.md:77,87`.
  Acceptance criteria: before external submission, `python scripts/verify_submission.py` rejects unresolved placeholders, example domains, and private URLs and passes only with public repo, MIT, downloadable debug APK/checksum, release-variant debug-hook absence proof, 200 backend, public YouTube duration <180s, actual session ID, English instructions, explicit pantry/equipment/preferences/cooking-photo transient-processing plus `store=false` disclosure, and Codex/GPT-5.6 narrative. `gh release view v0.1.0-demo` lists the debug APK and checksum. After confirmed Submit, the Devpost dashboard shows `Submitted`, `task-12-submission.json` contains a non-placeholder `https://openai.devpost.com/software/<slug>` URL and submitted timestamp, and an anonymous browser/curl request returns 200 with `LeftOVERS` and Apps for Your Life visible.
  QA scenarios: happy - anonymous clean-device install follows README and completes seeded real-cloud flow, then an anonymous browser opens the public Devpost page and matches title/category/repo/video, evidence `task-12-judge-run.png`, `task-12-demo.mp4`, `task-12-devpost-submitted.png`, and `task-12-submission.json`; failure - run the verifier against a copied submission record with status `Draft` or a missing YouTube/session/repository field, PASS when it exits nonzero with the exact field name and no Submit action occurs, evidence `task-12-verifier-red.txt`.
  Commit: Y | `docs(submission): publish judge-ready project evidence`

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [ ] F1. Plan compliance audit - independent reviewer maps C1-C6 and T1-T12 to code/tests/evidence, runs `python scripts/verify_submission.py`, rejects missing/indirect evidence, and writes `.omo/evidence/leftovers/f1-compliance.md` with unconditional APPROVE.
- [ ] F2. Code quality/security review - independent reviewer inspects full diff, auth/quota/photo/inventory paths, runs Android/backend/Docker gates plus dependency checks and canonical `scripts/scan_secrets.ps1`, and writes `f2-quality.md`; any scanner bypass beyond the single Firebase config path, unrestricted Firebase client key, test suppression, leaked secret, broad exception, or untyped boundary blocks approval.
- [ ] F3. Agent-executed real-device QA - exact invocation `cd android && ./gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.junited31.leftovers.e2e.RealCloudE2eTest"`, then install/launch on `SM_F711N`, drive seed->recipes->photo advice->completion->history with ADB/Compose instrumentation, capture screenshot/video/HTTP artifacts, and write `f3-device.md` APPROVE.
- [ ] F4. Scope fidelity - compare final tree/dependencies/UI against Must have/Must NOT have, verify release has no debug receiver/seed/reset, no cloud user data/photo persistence, no forbidden feature/dependency, and write `f4-scope.md` APPROVE.

## Commit strategy
- One Conventional Commit per todo using its listed subject; implementation and direct tests stay together. Never batch unrelated waves or commit runtime secrets/evidence tokens.
- Every commit must pass its targeted RED->GREEN proof before creation. T1 establishes buildable main; later commits may rely only on earlier committed contracts.
- Final documentation commit includes actual URLs/checksums/session ID only after anonymous verification. Tag `v0.1.0-demo` at the final green commit and publish the APK/checksum from that SHA.
- Plan provenance footer on implementation commits: `Plan: .omo/plans/leftovers.md`.

## Success criteria
- Public `junited31/LeftOVERS` MIT repository exists on `main`, build/test instructions work, and release `v0.1.0-demo` exposes the documented seedable debug APK plus SHA-256; the separately built release APK proves debug hooks are absent.
- Android app installs/runs on connected Galaxy Z Flip3 and retains pantry, equipment, recipe snapshots, active sessions, feedback, remaining quantities, final-photo paths, and history across restart/offline use.
- Seeded pantry/equipment request yields exactly three valid diverse recipes from GPT-5.6; ranking/filter/cooldown outputs match deterministic fixtures and use expiring/available ingredients.
- Cooking photo input returns structured observations/actions/confidence/safety note through the deployed service; cache/backend lifecycle, `store=false`, and logging evidence prove no retained step photo, provider-stored response, or request body while documentation accurately discloses transient processing.
- Meal completion rejects unit/version/over-use errors atomically, otherwise saves one log, correct remaining inventory, measurement adjustments, rating, notes, and future recommendation signal.
- History timeline/detail displays completed records offline with graceful missing-photo handling; debug reset cascades local data/photos and is absent from release.
- Backend proves Firebase auth, fixed atomic per-UID/global quotas with honest installation-fairness/global-cost-boundary semantics, request sizes, typed errors, exact `gpt-5.6` text/image preflight, two bounded validation retries, canonical secret-scan compliance, billing preflight, restricted Firebase client key, and judge-accessible deployment health.
- Android unit/lint/build/instrumentation, backend pytest, Docker build, focused real-cloud E2E, F1-F4, and submission verifier all exit 0 with captured evidence and no skipped/xfail tests added.
- README/submission artifacts contain real public repo/release/backend/YouTube/Devpost URLs, English testing instructions, Codex collaboration and GPT-5.6 narrative, MIT license, and current session ID; demo is public with audio and <180 seconds, Devpost dashboard is `Submitted`, and the public software page is anonymously reachable.
