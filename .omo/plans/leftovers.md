# LeftOVERS - Work Plan

## TL;DR (For humans)
**What you'll get:** A native Android app that remembers pantry quantities and kitchen equipment, suggests three varied recipes that use expiring ingredients, coaches the cook from a photo, updates leftovers after the meal, learns from feedback, and keeps an offline cooking history. It will include a protected GPT-5.6 service, a public testable APK, and judge-ready documentation and demo materials.

**Why this approach:** The retained source of truth stays on the phone for privacy and reliable offline access; only the selected pantry/preferences or cooking photo are sent transiently for AI processing. A small authenticated service protects the OpenAI key and enforces quotas, while deterministic validation and ranking keep ingredient use, equipment compatibility, variety, and feedback testable.

**What it will NOT do:** The first release will not add iOS or web clients, social/shopping/nutrition features, cloud sync, custom appliance management, recipe crawling, or automatic food-safety claims. It will not upload data in the background or retain cooking photos on the server.

**Effort:** XL
**Risk:** High - the deadline requires a native app, deployed multimodal AI service, real-device proof, public APK, and complete contest submission in one coordinated build.
**Decisions to sanity-check:** Keep pantry units strict instead of converting them automatically; keep sign-in invisible and local data unsynced; use fixed equipment choices; ship Android only; apply fixed daily AI quotas with no judge bypass.

Your next move: start implementation from this approved plan, or request the optional high-accuracy plan review first. Full execution detail follows below.

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

## Execution strategy
### Parallel execution waves
> Target 5-8 todos per wave. Fewer than 3 (except the final) means you under-split.
- Wave 1 foundation (3 todos): T1 repository/build skeleton, T2 Android domain/persistence contracts, T3 backend/auth/quota/OpenAI contracts. T2 and T3 run in parallel after T1.
- Wave 2 product loop (6 todos): T4 pantry/equipment and T5 Android network/photo lifecycle in parallel; T6 recipes; T7 cooking/photo advice; T8 completion/feedback; T9 history.
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
- [ ] 1. Scaffold the reproducible monorepo and build contracts
  What to do / Must NOT do: Create root `README.md`, `LICENSE`, `.gitignore`, `android/`, `backend/`, and `scripts/`; use Gradle 8.10.2, AGP 8.7.3, Kotlin/Compose plugin 2.0.21, Java 17, compile/target 35, minSdk 29, package `com.junited31.leftovers`, and Python package pins from the approved draft. Generate the wrapper and one Compose `MainActivity`; create FastAPI `GET /health`. Add no feature architecture or secret values.
  Parallelization: Wave 1 | Blocked by: none | Blocks: T2,T3
  References: `.omo/drafts/leftovers.md:28-47,49-53,73-77`; known-good build files cited there.
  Acceptance criteria: capture RED from absent `android/gradlew.bat` and `backend/app/main.py`; GREEN from repository root with `cd android && ./gradlew.bat :app:assembleDebug :app:lintDebug`, then in a fresh shell `cd backend && .venv/Scripts/python.exe -c "from app.main import app; assert app.title == 'LeftOVERS API'"`; from repository root, `git grep -nE '(sk-|AIza|BEGIN PRIVATE KEY)'` finds no secrets.
  QA scenarios: happy - `adb install -r android/app/build/outputs/apk/debug/app-debug.apk && adb shell am start -n com.junited31.leftovers/.MainActivity`, PASS when UIAutomator contains `LeftOVERS`, evidence `.omo/evidence/leftovers/task-1-home.xml`; failure - build a release variant and inspect `aapt dump badging`, PASS when package/minSdk/targetSdk mismatch would fail the scripted assertion, evidence `task-1-build-contract.txt`.
  Commit: Y | `build(scaffold): bootstrap Android and API projects`

- [ ] 2. Define canonical domain models, Room schema, and atomic inventory completion
  What to do / Must NOT do: Add `PantryItemEntity`, `RecipeSnapshotEntity`, `CookSessionEntity`, `MealLogEntity`, DAOs, `LeftoversDatabase`, JSON converters, and DataStore keys. Pantry units are only `g|ml|count`; quantities are `Long` milli-units, UI parsing accepts at most three decimals, and rows carry an integer version. Recipe snapshots bind usage by pantry UUID/source version/unit. Implement one Room transaction that re-reads rows, validates unique known IDs, unit/version/`0 <= actualUse <= quantity`, writes the immutable meal log snapshot, and subtracts usage. Never clamp, auto-convert, deduct untracked missing ingredients, or delete zero rows.
  Parallelization: Wave 1 | Blocked by: T1 | Blocks: T4,T6,T7,T8,T9 | Can parallelize with: T3
  References: `.omo/drafts/leftovers.md:16-21,31,51,56,63-69`; numeric completion rules appended under Decisions.
  Acceptance criteria: RED first for over-use, unit mismatch, stale version, and rollback; GREEN via `cd android && ./gradlew.bat :app:testDebugUnitTest --tests '*CompleteCookSessionTest' --tests '*LeftoversDatabaseTest'`; assert restart persistence and full rollback on every invalid row.
  QA scenarios: happy - instrumentation inserts two pantry rows, completes a session, restarts the activity, and asserts exact remaining quantities plus one log; failure - two coroutines complete against one version and exactly one succeeds while the other returns `StaleInventory`, evidence `task-2-inventory-transaction.xml`.
  Commit: Y | `feat(data): add local kitchen and cooking records`

- [ ] 3. Implement typed FastAPI contracts, Firebase auth, quotas, and GPT-5.6 adapters
  What to do / Must NOT do: Add `backend/app/{main,config,models,auth,quota,openai_client,prompts}.py`, Firestore-backed atomic counters, Pydantic request/response schemas, injected fakes, and endpoints `/health`, `/v1/recipes/generate`, `/v1/cooking/advice`. Recipe pantry rows contain UUID ID, integer version, name, canonical unit, positive `quantityMilliUnits`, and optional expiry; tracked uses echo unique known ID/version/unit with positive `proposedMilliUnits <= quantity`, while missing ingredients are separate and never deductible. Enforce 256 KiB JSON, 8 MiB photo, quotas 20/50 UID and 2000/5000 global per UTC day, HMAC-SHA256 UID doc IDs, typed 401/413/422/429/502, `store=false`, one schema/diversity retry only, bounded upload reads, close in `finally`, and redacted logs. Do not persist request content or accept missing auth.
  Parallelization: Wave 1 | Blocked by: T1 | Blocks: T5,T6,T7,T11 | Can parallelize with: T2
  References: `.omo/drafts/leftovers.md:33,40-47,57-60,70` plus appended quota/model/photo decisions; official model page cited there.
  Acceptance criteria: RED first for invalid token, oversize, concurrent quota, invalid schema, duplicate/unknown pantry IDs, version/unit/amount mismatch, duplicate recipes, upload close, `store=false`, and log redaction; GREEN with `backend/.venv/Scripts/python.exe -m pytest -q backend/tests` and `docker build -t leftovers-api:test backend`. Tests prove atomic 21st/51st rejection and global cap, UTC Retry-After, no model call on boundary failures, exactly one validation retry, and no response can bind an unrequested pantry row.
  QA scenarios: happy - `curl -i http://127.0.0.1:8000/health` returns 200 JSON and authenticated fake recipe/advice requests match schemas, evidence `task-3-api.json`; failure - 8 MiB+1 and expired token requests return 413/401 with zero stored bytes/model calls, evidence `task-3-boundaries.txt`.
  Commit: Y | `feat(api): add authenticated GPT cooking contracts`

- [ ] 4. Build equipment onboarding and pantry CRUD
  What to do / Must NOT do: Implement Compose navigation shell, fixed equipment checklist, pantry list/add/edit forms, canonical units, optional expiry, validation, and Room/DataStore persistence. Include induction, gas burner, microwave, oven, air fryer, blender, rice cooker, toaster, basic cookware. Add debug-source-set-only seed/reset broadcast; no custom equipment CRUD or release receiver.
  Parallelization: Wave 2 | Blocked by: T2 | Blocks: T6,T8,T10 | Can parallelize with: T5
  References: `.omo/drafts/leftovers.md:16-18,30,34-37,61,64,73-77`.
  Acceptance criteria: RED Compose tests for empty name, non-positive or over-three-decimal quantity, equipment persistence, edit/restart, and release receiver absence; GREEN with `cd android && ./gradlew.bat :app:testDebugUnitTest :app:assembleRelease :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.junited31.leftovers.ui.PantryEquipmentTest`, then `& "$env:ANDROID_HOME/build-tools/35.0.0/aapt.exe" dump xmltree app/build/outputs/apk/release/app-release-unsigned.apk AndroidManifest.xml` showing no debug receiver.
  QA scenarios: happy - seed by `adb shell am broadcast -a com.junited31.leftovers.DEBUG_SEED`, restart, and capture pantry/equipment screens, evidence `task-4-pantry.png` and `task-4-equipment.png`; failure - submit blank/zero input and PASS when inline error appears and DB count is unchanged, evidence `task-4-validation.xml`.
  Commit: Y | `feat(pantry): track ingredients and kitchen equipment`

- [ ] 5. Add Android auth/API client and ephemeral photo lifecycle
  What to do / Must NOT do: Add Firebase anonymous token provider, OkHttp client, typed error mapping, native `PickVisualMedia` + `TakePicture`, app `FileProvider`, 1280px/quality-80 JPEG compression, cache `finally` deletion, cancellation handling, and startup sweep older than 24h. Do not embed OpenAI secrets, retry automatically, upload in background, or use CameraX.
  Parallelization: Wave 2 | Blocked by: T3 | Blocks: T6,T7,T10 | Can parallelize with: T4
  References: `.omo/drafts/leftovers.md:31-33,44,52-53,57,60,66,73-77` plus appended photo/offline decisions.
  Acceptance criteria: RED for success/401/413/422/429/502/cancel/process-restart cache cleanup; GREEN via `cd android && ./gradlew.bat :app:testDebugUnitTest --tests '*LeftoversApiTest' --tests '*PhotoLifecycleTest' :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.junited31.leftovers.photo.PhotoPickerTest`. APK string scan contains no `OPENAI_API_KEY` or secret pattern.
  QA scenarios: happy - MockWebServer receives <=8 MiB JPEG and cache directory is empty after response, evidence `task-5-photo-lifecycle.json`; failure - cancel a delayed request and restart app, PASS when no network retry occurs and stale cache is swept, evidence `task-5-cancel.txt`.
  Commit: Y | `feat(network): connect secure AI and photo transport`

- [ ] 6. Generate, validate, rank, and display three diverse recipes
  What to do / Must NOT do: Implement backend recipe prompt/schema, Android recipe request/response, deterministic normalizer/fingerprint/profile/ranker, candidate cards, scoring explanations, missing ingredients, and snapshot save. Hard-filter equipment/cooldowns; require exactly three unique normalized titles/fingerprints, at least two normalized cuisines, and at least two normalized primary techniques. Novelty compares normalized ingredient-name sets, never hash strings. Use the approved 0.45/0.25/0.20/0.10 algorithm/tie-breaks and strict pantry-row binding. No crawler, recipe corpus, ML, or server persistence.
  Parallelization: Wave 2 | Blocked by: T2,T3,T4,T5 | Blocks: T7,T8,T10
  References: `.omo/drafts/leftovers.md:18,33-35,54-55,65,68,73-77` plus appended exact ranking/fingerprint definitions.
  Acceptance criteria: RED fixtures for missing equipment, three-vs-two candidates, duplicate title/fingerprint, only-one-cuisine, only-one-technique, unknown/duplicate pantry ID, unit/version/amount mismatch, no history, exact tag-score normalization, ingredient-set Jaccard, 20-log truncation, 30-day boundary, stable ties, and expiry weights; GREEN with Android `*RecommendationRankerTest` and backend `test_recipe_generation.py`. Same fixture must produce byte-stable ordered IDs across three runs.
  QA scenarios: happy - authenticated seeded pantry request returns exactly three candidates and the app screenshot exposes usage/equipment/rank reasons, evidence `task-6-recipes.json` and `task-6-recipes.png`; failure - backend fake returns duplicates twice, PASS on typed 422 and unchanged Room snapshot count, evidence `task-6-diversity-failure.txt`.
  Commit: Y | `feat(recipes): recommend diverse pantry-first meals`

- [ ] 7. Implement step-by-step cooking and safe photo coaching
  What to do / Must NOT do: Add active-session resume, step navigation/timers as display-only durations, camera/gallery attachment, cooking-advice request, structured advice UI, confidence, and mandatory safety note. Preserve active steps offline. Never claim doneness/safety from the image or silently move to the next step.
  Parallelization: Wave 2 | Blocked by: T2,T3,T5,T6 | Blocks: T8,T10
  References: `.omo/drafts/leftovers.md:19,52-53,57-60,66,73-77`.
  Acceptance criteria: RED for resume after process recreation, safety-note omission, malformed response, offline retry UI, cancellation cleanup, and no automatic step advance; GREEN with Android `*CookingSessionTest`, connected `CookingPhotoFlowTest`, and backend `test_cooking_advice.py`.
  QA scenarios: happy - upload the generated/licensed cooking fixture at step 2 and `curl`/UI both show observations, actions, confidence, safety note, evidence `task-7-cooking-advice.json` and `task-7-cooking.png`; failure - network disabled with `adb shell cmd connectivity airplane-mode enable`, PASS when steps remain usable, retry is explicit, no DB mutation/re-upload occurs, then restore connectivity in cleanup receipt.
  Commit: Y | `feat(cooking): guide steps with photo advice`

- [ ] 8. Complete meals, retain leftovers, and learn deterministic preferences
  What to do / Must NOT do: Build actual-use editor, rating 1-5, ingredient measurement adjustments, notes, recommend-again toggle, optional final-photo retention, atomic completion call, profile derivation, exact fingerprint cooldown, and result confirmation. Keep adjustments as normalized ingredient name, preferred amount in milli-units, canonical unit, note, and completion timestamp; newest wins per name+unit, and the latest 20 unique keys become the next recipe request's `measurementHints`. Do not add a conversion engine or ML.
  Parallelization: Wave 2 | Blocked by: T2,T4,T6,T7 | Blocks: T9,T10
  References: `.omo/drafts/leftovers.md:20,51-56,67-68,75-77` plus appended unit/ranking/cooldown definitions.
  Acceptance criteria: RED for actual use edits, zero remaining rows, over-use/unit/stale rollback, exact preference tag-score formula, positive/negative recommendation, exact 30-day expiry, newest-wins adjustment precedence, 20-key truncation, next-request `measurementHints`, and final-photo path; GREEN with `*CompleteMealFlowTest`, `*PreferenceProfileTest`, and connected completion test.
  QA scenarios: happy - complete seeded recipe and assert exact inventory, one immutable log, retained photo, and next ranking boost, evidence `task-8-feedback.json`; failure - change pantry in a competing transaction before completion, PASS when UI reports stale inventory and no row/log/photo mutation occurs, evidence `task-8-stale.txt`.
  Commit: Y | `feat(feedback): update leftovers and future ranking`

- [ ] 9. Present persistent cooking history and details
  What to do / Must NOT do: Implement reverse-chronological history timeline and detail with final photo, recipe snapshot, actual use, remaining-after values, rating, adjustments, notes, and recommend-again state. Ensure app restart/offline access and debug-reset cascade cleanup. Do not add sharing, analytics, or cloud sync.
  Parallelization: Wave 2 | Blocked by: T2,T8 | Blocks: T10
  References: `.omo/drafts/leftovers.md:21,31,51-52,61,69,73-77`.
  Acceptance criteria: RED for empty state, ordering ties by UUID, restart/offline detail, missing photo fallback, reset cascade; GREEN with `*HistoryRepositoryTest` and connected `HistoryScreenTest`.
  QA scenarios: happy - seed two completed meals, restart offline, open newest detail, capture timeline/detail, evidence `task-9-history.png`; failure - delete the referenced test photo outside the app, PASS when fallback renders without crash or DB rewrite, evidence `task-9-missing-photo.xml`.
  Commit: Y | `feat(history): show completed cooking records`

- [ ] 10. Harden full Android behavior, accessibility, offline states, and release boundaries
  What to do / Must NOT do: Add content descriptions/semantics, short-screen scrolling, state restoration, typed error copy, debug-vs-release source-set checks, database migration/version tests, dependency/security scan, and one focused E2E with fake backend. Do not broaden product scope or add generic design-system/framework layers.
  Parallelization: Wave 3 | Blocked by: T4-T9 | Blocks: T11,T12
  References: all draft components/scope; `.omo/drafts/leftovers.md:16-21,63-77`.
  Acceptance criteria: full Android gate exits 0 with XML totals and zero lint errors; `cd android && ./gradlew.bat :app:dependencies` plus repository-root secret/debug-hook scans pass; connected E2E covers pantry->recipe->cook->feedback->history on `SM_F711N` including a short viewport and offline read.
  QA scenarios: happy - run focused `LeftoversFlowE2eTest` and capture final history screen, evidence `task-10-e2e.png`; failure - inject 401/413/422/429/502 sequentially and PASS when typed copy/retry policy matches and DB/photo cache hashes remain unchanged, evidence `task-10-errors.json`.
  Commit: Y | `fix(app): harden end-to-end cooking flow`

- [ ] 11. Provision isolated Firebase/Cloud Run and prove real GPT-5.6 traffic
  What to do / Must NOT do: Add idempotent `scripts/bootstrap_cloud.ps1` and `scripts/deploy_backend.ps1`; create/select project `leftovers-019f706b`, register package, enable anonymous auth/Firestore/required APIs, create `QUOTA_HASH_KEY` and `OPENAI_API_KEY` secrets, deploy `leftovers-api` to `asia-northeast3`, inject URL into Android BuildConfig, restrict Firebase API key, and run text-structured plus synthetic-PNG image preflights. Never touch unrelated Firebase projects or print secrets/tokens.
  Parallelization: Wave 3 | Blocked by: T3,T10 | Blocks: T12
  References: `.omo/drafts/leftovers.md:32-33,41,47,57,60,70,79-80` plus appended cloud/model decisions.
  Acceptance criteria: scripts are idempotent and stop if active project differs; `curl -i https://<service>/health` returns 200; invalid token 401; authenticated exact `gpt-5.6` structured/image smoke returns valid schema; Firestore shows only HMAC quota docs; `docker build` and backend tests pass. External create/deploy actions require action-time confirmation.
  QA scenarios: happy - focused connected `RealCloudE2eTest` produces three recipes and photo advice against deployed service, evidence `task-11-cloud.json`; failure - expired token and concurrent quota probes return 401/one atomic 429 without model overrun, evidence `task-11-quota.txt`. Cleanup receipt removes only temporary local tokens/files, not the requested deployment.
  Commit: Y | `ops(cloud): deploy protected LeftOVERS API`

- [ ] 12. Publish judge-ready APK, documentation, demo, and submission evidence
  What to do / Must NOT do: Complete English `README.md` with setup/sample/test/debug-APK/backend instructions, Codex decision narrative, GPT-5.6 usage, explicit disclosure that selected pantry/preferences and cooking photos leave the device for transient AI processing with `store=false`, privacy/safety, current session ID `019f706b-b713-7780-a456-f63ab18b173a`; add `docs/submission.md`, `docs/demo-script.md`, screenshots, generated/licensed fixture provenance, and `scripts/verify_submission.py`. Attach `android/app/build/outputs/apk/debug/app-debug.apk` to public GitHub release `v0.1.0-demo`; document its ADB-only debug seed/reset flow, while separately proving the release APK contains no such receiver. Record <180s device demo, add audio narration, upload Public to YouTube through authenticated browser after action-time confirmation, and replace every URL field with real reachable values.
  Parallelization: Wave 3 | Blocked by: T10,T11 | Blocks: F1-F4
  References: official rules/model links in `.omo/drafts/leftovers.md:39-47`; approved submission scope `.omo/drafts/leftovers.md:70-71` plus appended judge-delivery decision.
  Acceptance criteria: `python scripts/verify_submission.py` rejects unresolved placeholders, example domains, and private URLs and passes only with public repo, MIT, downloadable debug APK/checksum, release-variant debug-hook absence proof, 200 backend, public YouTube duration <180s, actual session ID, English instructions, transient-processing/`store=false` disclosure, and Codex/GPT-5.6 narrative. `gh release view v0.1.0-demo` lists the debug APK and checksum.
  QA scenarios: happy - anonymous clean-device install follows README and completes seeded real-cloud flow; evidence `task-12-judge-run.png`, `task-12-demo.mp4`, and `task-12-submission.json`; failure - run verifier against a copied README with missing YouTube/session ID, PASS when it exits nonzero with exact field name, evidence `task-12-verifier-red.txt`.
  Commit: Y | `docs(submission): publish judge-ready project evidence`

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [ ] F1. Plan compliance audit - independent reviewer maps C1-C6 and T1-T12 to code/tests/evidence, runs `python scripts/verify_submission.py`, rejects missing/indirect evidence, and writes `.omo/evidence/leftovers/f1-compliance.md` with unconditional APPROVE.
- [ ] F2. Code quality/security review - independent reviewer inspects full diff, auth/quota/photo/inventory paths, runs Android/backend/Docker gates plus dependency and secret scans, and writes `f2-quality.md`; any test suppression, leaked secret, broad exception, or untyped boundary blocks approval.
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
- Backend proves Firebase auth, fixed atomic per-UID/global quotas, request sizes, typed errors, exact `gpt-5.6` text/image preflight, zero secret leakage, and judge-accessible deployment health.
- Android unit/lint/build/instrumentation, backend pytest, Docker build, focused real-cloud E2E, F1-F4, and submission verifier all exit 0 with captured evidence and no skipped/xfail tests added.
- README/submission artifacts contain real public repo/release/backend/YouTube URLs, English testing instructions, Codex collaboration and GPT-5.6 narrative, MIT license, and current session ID; demo is public with audio and <180 seconds.
