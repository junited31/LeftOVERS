---
slug: leftovers
status: approved
intent: clear
pending-action: validate and commit .omo/plans/leftovers.md
approach: Android local-first app with a minimal authenticated FastAPI GPT-5.6 proxy
---

# Draft: leftovers

## Components (topology ledger)
<!-- Lock the SHAPE before depth. One row per top-level component that can succeed or fail independently. -->
<!-- id | outcome (one line) | status: active|deferred | evidence path -->
| id | outcome | status | evidence path |
| --- | --- | --- | --- |
| C1 | Persistent pantry quantities and expiry dates survive restart and are editable | active | `.omo/evidence/leftovers/task-4-pantry.png` |
| C2 | Kitchen equipment selection constrains every generated recipe | active | `.omo/evidence/leftovers/task-4-equipment.png` |
| C3 | GPT-5.6 returns three diverse, equipment-compatible recipes ranked to use available and expiring ingredients | active | `.omo/evidence/leftovers/task-6-recipes.json` |
| C4 | A cooking session shows steps and analyzes an uploaded/captured step photo without claiming visual food safety | active | `.omo/evidence/leftovers/task-7-cooking-advice.json` |
| C5 | Completion deducts confirmed ingredient usage and records rating, measurement adjustments, and recommend-again choice | active | `.omo/evidence/leftovers/task-8-feedback.json` |
| C6 | A history timeline and detail screen expose completed dishes and feed deterministic future recommendation preferences | active | `.omo/evidence/leftovers/task-9-history.png` |

## Open assumptions (announced defaults)
<!-- Record any default you adopt instead of asking, so the user can veto it at the gate. -->
<!-- assumption | adopted default | rationale | reversible? -->
| assumption | adopted default | rationale | reversible? |
| --- | --- | --- | --- |
| Product identity | Display name and GitHub repository `LeftOVERS`; Android package `com.junited31.leftovers` | User explicitly chose exact capitalization; package names must be lowercase | Yes, before first release |
| Repository access | Public repository with MIT license | Fastest judge access and compliant with Devpost public-repo licensing rule | Yes |
| Platform | Android native, Kotlin + Jetpack Compose, minSdk 29, compile/target SDK 35, Java 17 | User chose native mobile; this exact toolchain is installed and previously device-tested on this host | Yes, but costly after implementation |
| User data | The retained source of truth stays on-device; recipe generation transiently sends the selected pantry/equipment/preference context, and photo advice transiently sends the selected step image | Meets remembered-leftovers requirements while honestly disclosing the minimum AI processing boundary | Yes |
| Cloud identity | Firebase anonymous authentication is transport protection only; no login/account UI | Protects the OpenAI proxy without turning the app into an account product | Yes |
| AI boundary | FastAPI proxy uses OpenAI Responses API model `gpt-5.6`; no OpenAI key in the APK | Required by hackathon and protects the API secret | Yes |
| Recipe set | Exactly three candidates with unique titles/fingerprints, at least two cuisines, and at least two primary techniques | Makes diversity deterministic, observable, and achievable without forcing three unrelated cuisines and techniques | Yes |
| Equipment catalog | induction, gas burner, microwave, oven, air fryer, blender, rice cooker, toaster, and basic cookware | Covers the stated equipment without custom-equipment CRUD | Yes |
| Language | Korean app UI; English README, testing guide, and Devpost copy | Matches user context while satisfying submission language rules | Yes |
| Test strategy | TDD for domain/backend boundaries, Compose instrumentation for user flows, real-device final gate | The state transitions and AI contracts are subtle and need failing-first coverage | No for this plan |

## Findings (cited - path:lines)
- Official rules: `https://openai.devpost.com/rules` requires a working Codex/GPT-5.6 project, <3-minute public YouTube demo, repository URL, Codex collaboration README, `/feedback` session ID, and free judge access.
- Official model contract: `https://developers.openai.com/api/docs/models/gpt-5.6-sol` confirms image input, Responses API, function calling, and Structured Outputs.
- Current host: JDK 17 is on PATH; SDK platforms 34, 35, 36, and 36.1 are installed; adb currently sees `SM_F711N`.
- Known-good local Android toolchain: `D:/workspace/develop/meal-health-ai-app/android/build.gradle.kts:1` uses AGP 8.7.3 and Kotlin 2.0.21; `D:/workspace/develop/meal-health-ai-app/android/app/build.gradle.kts:10` uses compile/target 35 and minSdk 29; `D:/workspace/develop/meal-health-ai-app/android/gradle/wrapper/gradle-wrapper.properties:3` uses Gradle 8.10.2.
- Native photo capture pattern: `D:/workspace/develop/meal-health-ai-app/android/app/src/main/java/com/example/mealhealthpoc/capture/MealPhotoPicker.kt:31` uses `FileProvider` + activity-result contracts, avoiding a CameraX dependency for a still-photo-only flow.
- Known-good backend baseline: `D:/workspace/develop/meal-health-ai-app/backend/requirements.txt:1` demonstrates FastAPI + Pydantic v2 + Firebase Admin + pytest on this machine.
- Current PyPI resolution on 2026-07-18: openai 2.46.0, fastapi 0.139.2, uvicorn 0.51.0, pydantic 2.13.4, pydantic-settings 2.14.2, firebase-admin 7.5.0, pytest 9.1.1.
- Firebase CLI is installed and authenticated; accessible projects were observed, but the plan must create/use an isolated LeftOVERS project rather than mutate an unrelated existing app by default.

## Decisions (with rationale)
- Use one repository with `android/` and `backend/`; this is the smallest layout that keeps the native client and secret-holding API independently testable.
- Use Room for `PantryItemEntity`, `RecipeSnapshotEntity`, `CookSessionEntity`, and `MealLogEntity`; use DataStore for selected equipment, installation settings, and pending user preferences.
- Store a retained final-dish photo in Android app-private files and only its path in Room. Cooking-step photos are compressed to a 1280px longest edge, sent once for advice, and deleted from cache after the request.
- Use native Photo Picker and `TakePicture` contracts. Do not add CameraX because the product needs single still images, not a custom camera preview.
- Hard-filter generated candidates by equipment, then rank with `0.45 ingredient coverage + 0.25 expiry urgency + 0.20 preference + 0.10 novelty`. A valid set has exactly three unique normalized titles and fingerprints, at least two normalized cuisines, and at least two normalized primary techniques.
- Build a compact preference profile locally from the latest 20 meal logs. `recommendAgain=false` suppresses the exact recipe fingerprint for 30 days; `true` boosts matching cuisine/technique tags. Measurement adjustments are keyed by normalized ingredient name plus canonical unit; the newest adjustment wins, at most the latest 20 unique keys are sent as later recipe-generation context.
- Inventory is never deducted when a recipe is merely selected. Completion presents editable actual usage, then one Room transaction validates the latest row versions, writes the meal log, and decrements quantities; invalid or excessive usage aborts instead of clamping, while valid zero-quantity items remain editable rather than disappearing silently.
- Backend endpoints: `GET /health`, `POST /v1/recipes/generate`, and `POST /v1/cooking/advice`. Both POST routes require Firebase bearer tokens, enforce request-size and per-UID daily quotas, and return typed 401/413/422/429/502 errors.
- Use Pydantic schemas as the Structured Outputs contract. A pantry request row carries UUID `pantryItemId`, integer `version`, normalized name, canonical unit, integer `quantityMilliUnits`, and optional expiry date. A tracked recipe use echoes the ID/version/unit with positive integer `proposedMilliUnits`; IDs must be unique, present in the request, unit-identical, and not exceed availability. Unknown/duplicate/mismatched tracked rows fail closed with 422; missing ingredients use separate untracked name/amount/unit fields and can never be deducted.
- Cooking advice returns `status`, `observations`, `nextActions`, `confidence`, and `safetyNote`; prompts and UI explicitly say images cannot prove doneness or food safety and recommend time/temperature checks where relevant.
- Retained copies of pantry, history, feedback, recipe snapshots, and photos stay on-device. Recipe generation transiently sends pantry/equipment/preference hints and cooking advice transiently sends the selected step image through the backend to OpenAI. Every Responses API request sets `store=false`; the backend does not persist request content, and Firestore stores only hashed UID/day quota counters.
- Provide seeded demo data and a debug-only reset action so judges can exercise the full flow without manual pantry setup; release behavior remains normal local persistence.
- Pantry quantities use only canonical `g`, `ml`, or `count`. Generated tracked-ingredient usage must carry the same pantry unit; there is no implicit conversion. Completion validates `0 <= actualUse <= currentQuantity` against the latest row version and aborts the entire Room transaction on unit mismatch, over-consumption, or stale concurrent state instead of clamping.
- Recipe fingerprints are SHA-256 of normalized `cuisine|primaryTechnique|sorted ingredient names`; normalization lowercases, trims, collapses whitespace, and applies Unicode NFKC. Ranking first hard-filters equipment and active 30-day `recommendAgain=false` fingerprints, validates exactly three unique normalized titles/fingerprints with at least two cuisines and two techniques, then scores `0.45 coverage + 0.25 expiry + 0.20 preference + 0.10 novelty`; ties resolve by coverage, expiry, then normalized title ascending.
- Coverage is the fraction of distinct non-staple pantry items used. Expiry is the fraction of total expiry weight captured by used items (`1.0` <=3 days, `0.5` <=7 days, `0.1` later/no date). For each cuisine or technique tag in the newest 20 logs, let `signal = clamp((rating-3)/2 + (recommendAgain ? 0.5 : -1.0), -1, 1)` and `tagScore = (sum(signal)+matchCount)/(2*matchCount)`; an unseen tag scores `0.5`, and a candidate preference is the mean of its cuisine and technique tag scores. Novelty is `1 - max Jaccard` between the candidate normalized ingredient-name set and those from the latest five meal logs; no history yields novelty `1.0`. Cooldowns use UTC instants and expire exactly at 30*24 hours.
- Backend limits: recipe JSON <=256 KiB, advice multipart <=8 MiB, Android photo longest edge <=1280px JPEG quality 80, 20 recipe generations/UID/UTC day, 50 advice calls/UID/UTC day, global caps 2,000/5,000 respectively, and no judge bypass. Firestore transactions increment per-UID and global counters atomically. Document IDs use HMAC-SHA256(uid, `QUOTA_HASH_KEY`); 429 includes `Retry-After` to next UTC midnight.
- Backend reads advice uploads in bounded chunks, closes `UploadFile` in `finally`, never writes application-owned photo files, and redacts bodies/tokens from logs. Android deletes step-photo cache files in `finally` after success, typed failure, or cancellation and sweeps cache files older than 24 hours at startup. Debug reset deletes retained final photos and Room/DataStore state; release has no reset receiver or seed entry point.
- Offline contract: pantry, equipment, recipe snapshots, active-session steps, and history remain available. Generate/advice actions show typed retryable errors and never mutate local state; no background retry or automatic photo re-upload occurs. Completion is one local transaction and works offline.
- Model reliability: preflight performs one text Structured Outputs call and one synthetic PNG image-input call against exact alias `gpt-5.6`. Each production request gets one model attempt plus at most one retry only for schema/diversity validation failure; transport/auth/quota errors are never retried automatically.
- Cloud identity is isolated: Firebase/GCP project ID `leftovers-019f706b`, Android app package `com.junited31.leftovers`, Cloud Run service `leftovers-api`, region `asia-northeast3`. Runtime secrets are `OPENAI_API_KEY` and `QUOTA_HASH_KEY`; Firebase Admin uses the Cloud Run service account through Application Default Credentials. Only `google-services.json` may be committed, while service-account files and secret values must remain ignored.
- Judge delivery uses a public GitHub release `v0.1.0-demo` with the debug APK so the documented ADB-only seed/reset receiver is available; the release APK is built only to prove production boundaries and contains no receiver. Delivery also includes a deployed `/health` and authenticated smoke path, an English README/testing/privacy guide, a public <3-minute YouTube demo URL, and the actual current Codex `/feedback` session ID. Placeholders block the submission-readiness task.

## Scope IN
- Android onboarding/equipment selection, pantry CRUD, expiry date and quantity/unit editing.
- GPT-5.6 recipe generation with three diverse candidates, ingredient/equipment fit, ranking explanation, and missing-ingredient disclosure.
- Step-by-step cooking session, camera/gallery photo input, structured photo coaching, and explicit visual-safety limitation.
- Completion confirmation, actual ingredient usage, transactional remaining-inventory update, 1-5 satisfaction, measurement adjustments, notes, and recommend-again choice.
- Preference-aware reranking and exact-recipe cooldown based on local meal logs.
- History timeline, history detail, retained final photo, and recipe/adjustment display.
- Minimal authenticated FastAPI proxy, ephemeral image handling, quota counters, Docker/Cloud Run deployment, and Firebase anonymous auth.
- English README/submission checklist, sample data, APK/release instructions, Codex collaboration notes, and the actual `/feedback` session ID.

## Scope OUT (Must NOT have)
- No iOS, web app, tablet-specific layout, social/community sharing, shopping list, nutrition/health tracking, receipt scanning, barcode scanning, voice control, or smart-appliance control.
- No user-visible account, cloud backup/sync, Firestore mirror of Room data, server-side photo history, or recommendation ML training pipeline.
- No recipe marketplace/crawler, copyrighted recipe corpus, payments, ads, push notifications, or custom equipment management.
- No CameraX/custom camera preview, multi-module clean-architecture framework, generic repository interfaces with one implementation, or speculative plugin system.

## Open questions
- None. The approved design and announced defaults are sufficient to write the plan. Cloud project creation remains an execution-time external action and must use a new isolated project ID.

## Approval gate
status: approved
approved-by: user
approved-at: 2026-07-18T00:05:00+09:00
pending-action: validate and commit .omo/plans/leftovers.md
<!-- When exploration is exhausted and unknowns are answered, set status: awaiting-approval. -->
<!-- That durable record is the loop guard: on a later turn read it and resume at the gate instead of re-running exploration. -->
