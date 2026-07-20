# LeftOVERS submission readiness

This is the canonical machine-readable publication record and human checklist. It remains `Draft` until real external artifacts exist and the Devpost submission has been confirmed. The verifier must stay red before then.

<!-- submission-record:start -->
```json
{
  "status": "Draft",
  "devpost_url": "",
  "submitted_at_utc": "",
  "repository_url": "https://github.com/junited31/LeftOVERS",
  "release_url": "",
  "apk_url": "",
  "checksum_url": "",
  "backend_health_url": "https://leftovers-api-rlm4ngxglq-du.a.run.app/health",
  "youtube_url": "",
  "session_id": "019f706b-b713-7780-a456-f63ab18b173a",
  "apk_path": "android/app/build/outputs/apk/debug/app-debug.apk",
  "checksum_path": "dist/app-debug.apk.sha256",
  "release_apk_path": "android/app/build/outputs/apk/release/app-release-unsigned.apk",
  "release_debug_hook_proof_path": "dist/release-debug-hooks.json",
  "demo_media_path": "dist/leftovers-demo.mp4",
  "demo_media_evidence_path": "dist/leftovers-demo-media.json",
  "readme_path": "README.md",
  "submission_path": "docs/submission.md",
  "demo_script_path": "docs/demo-script.md",
  "license_path": "LICENSE"
}
```
<!-- submission-record:end -->

## Completed locally

- Public repository and deployed backend URLs are known.
- The backend health, authenticated GPT-5.6 text/image flow, real Android device flow, offline history, and cache cleanup have evidence.
- The current Codex session ID is recorded.
- English build, test, ADB seed/reset, privacy, quota, AI, license, and provenance documentation is present.
- The verifier requires a strict, release-APK-SHA-bound JSON proof with zero manifest, DEX, resource, and archive debug-hook matches.

## Pending external publication

- Build the final debug and release APKs from the publication commit.
- Write `dist/app-debug.apk.sha256` from the exact debug APK.
- Create `dist/release-debug-hooks.json` from the final release APK and the four release hook scans.
- Create public GitHub release `v0.1.0-demo`; attach `app-debug.apk` and `app-debug.apk.sha256`.
- Record `dist/leftovers-demo.mp4`, use `ffprobe` to observe a duration below 180 seconds and at least one audio stream, then write those observations and the exact media SHA-256 to `dist/leftovers-demo-media.json`.
- Watch that exact media file end to end and upload it as Public to YouTube.
- Populate the Apps for Your Life Devpost entry with the description, technologies, repository, release/testing instructions, video, screenshots, Codex/GPT-5.6 explanation, and session ID.
- Immediately before Devpost Submit, obtain action-time confirmation; then submit, verify dashboard state `Submitted`, and anonymously verify the public page.
- Replace every blank field above with the real reachable value and set the status/timestamp only after submission succeeds.

The local media proof and signed-out public YouTube check are distinct gates: the former binds duration and audio evidence to the uploaded bytes, while the latter proves public reachability. No release, YouTube, or Devpost URL is guessed here. `python scripts/verify_submission.py` is expected to name pending fields and exit nonzero in this state.

## Devpost copy

**Tagline:** Cook what you already have, with local memory and structured AI guidance.

**What it does:** LeftOVERS combines pantry quantities, expiry dates, available equipment, and local meal feedback to generate exactly three diverse recipes. It guides a selected recipe, can analyze an explicitly selected cooking-step photo, commits ingredient use atomically, and keeps the resulting history offline.

**How it was built:** The Android client uses Kotlin, Jetpack Compose, Room, DataStore, Firebase anonymous authentication, and OkHttp. A FastAPI service on Cloud Run verifies Firebase tokens, applies transactional Firestore quotas, and calls the OpenAI Responses API with GPT-5.6 Structured Outputs and `store=false`.

**Safety and privacy:** Product data remains on-device. Selected pantry, equipment, preference/history context, and an explicitly selected cooking-step photo cross the device only for transient AI processing. Photo guidance is observational and cannot certify food safety.

**Codex:** Codex supported planning, TDD, review, deployment diagnosis, and evidence collection. Session: `019f706b-b713-7780-a456-f63ab18b173a`.
