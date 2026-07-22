# LeftOVERS submission readiness

This is the canonical machine-readable publication record and human checklist. It remains `Draft` until real external artifacts exist and the Devpost submission has been confirmed. The verifier must stay red before then.

<!-- submission-record:start -->
```json
{
  "schema_version": 2,
  "publication_source_sha": "0000000000000000000000000000000000000000",
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
- The backend health, original authenticated GPT-5.6 text/image flow, Android device flow, offline history, and cleanup have historical evidence. The current code routes production inference through fixed Vertex AI Gemini models with ADC; final publication must use post-cutover proof.
- The earlier 103.366667-second narrated GPT-5.6 demo and its three physical-device runs remain historical-only. They cannot satisfy the active source/visual/artifact or publication gates after the Vertex and bilingual expansion.
- The current Codex session ID is recorded.
- English build, test, ADB seed/reset, privacy, quota, AI, license, and provenance documentation is present, including [the current privacy distinctions](privacy.md).
- The verifier requires strict schema-v2 submission, release, and media records with one identical lowercase publication source SHA.

## Pending external publication

- Build the final debug and release APKs from the publication commit.
- Write `dist/app-debug.apk.sha256` from the exact debug APK.
- Create `dist/release-debug-hooks.json` from the final release APK and the four release hook scans.
- Create public GitHub release `v0.1.0-demo`; attach `app-debug.apk` and `app-debug.apk.sha256`.
- Capture the complete fresh 11-state bilingual expansion flow, validate its PNG/XML manifest and canonical artifact record, then create a new narrated publication demo from source-bound evidence.
- Copy only the newly approved demo to `dist/leftovers-demo.mp4`, then regenerate strict schema-v2 `dist/leftovers-demo-media.json` from those exact bytes and the same `publication_source_sha`.
- Watch that exact publication media end to end and upload it as Public to YouTube.
- Populate the Apps for Your Life Devpost entry with the description, technologies, repository, release/testing instructions, video, screenshots, Codex contribution, current Vertex runtime, historical GPT-5.6/OpenAI contribution, and session ID.
- Immediately before Devpost Submit, obtain action-time confirmation; then submit, verify dashboard state `Submitted`, and anonymously verify the public page.
- Replace the all-zero source placeholder with the approved 40-character publication source SHA before creating evidence. The source commit must exist, be an ancestor of HEAD, and remain byte-identical except for the verifier's exact post-source allowlist. Replace every blank public field and set status/timestamp only after submission succeeds.

The local media proof and signed-out public YouTube check are distinct gates: the former binds duration and audio evidence to reviewed bytes, while the latter proves public reachability. No release, YouTube, or Devpost URL is guessed here. `python scripts/verify_submission.py` is expected to name pending fields and exit nonzero in this state.

Expansion T12 uses one production command after committing its allowlisted evidence:

```powershell
python scripts\verify_submission.py --expansion-evidence .omo/evidence/leftovers-expansion/task-12 --source-sha <40-hex-approved-source> --apk-sha <64-hex-apk> --device-serial <serial> --device-model <model> --run-start <RFC3339-UTC> --run-end <RFC3339-UTC>
```

It must report zero source/visual/artifact issues and one lowercase `artifact_record_digest`. Any recapture changes included bytes, requires artifact regeneration, and invalidates every downstream review receipt.

## Devpost copy

**Tagline:** Cook what you already have, with local memory and structured AI guidance.

**What it does:** LeftOVERS combines pantry quantities, expiry dates, available equipment, and local meal feedback to generate exactly three diverse recipes. It guides a selected recipe, can analyze an explicitly selected cooking-step photo, commits ingredient use atomically, and keeps the resulting history offline.

**How it was built:** The Android client uses Kotlin, Jetpack Compose, Room, DataStore, Firebase anonymous authentication, and OkHttp. A FastAPI service on Cloud Run verifies Firebase tokens, applies transactional Firestore quotas, and calls fixed Vertex AI Gemini models through service-account ADC with schema validation and a bounded best-effort transient fallback.

**Safety and privacy:** Product data and final-dish photos remain on-device. Selected pantry, equipment, preference/history context, and an explicitly selected cooking-step photo cross the device only for transient Vertex inference. The backend does not persist application-owned request bodies/photos and logs metadata only; provider terms still apply and no Zero Data Retention guarantee is claimed. Photo guidance is observational and cannot certify food safety.

**Codex and model history:** Codex supported planning, TDD, review, deployment diagnosis, and evidence collection. GPT-5.6/OpenAI materially powered the original validated implementation; Vertex Gemini is the current runtime. Session: `019f706b-b713-7780-a456-f63ab18b173a`.
