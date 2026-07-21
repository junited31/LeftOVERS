# Narrated demo script

The final local judge demo is 103.366667 seconds and edits exactly three fresh physical-device capture runs. Run 1 contains only local-fixture pantry and equipment states. Run 2 is the real-cloud recipe-card capture. Run 3 is one new real-cloud cooking, full-advice, completion, success, and offline-history E2E recording. Exactly Runs 2 and 3 exercised the deployed real cloud. The edit is not presented as one continuous run, and no continuous-run claim is made.

| Final time | Device action | Narration / disclosure |
|---|---|---|
| 0-1s | Hold one genuine, stable Run 1 pantry app frame sequence. | Open on a clean project UI frame with no personal location, external device UI, or compositor boundary. |
| 1-9s | Show the seeded pantry from Run 1. | Explain that pantry and meal history remain local. |
| 9-18s | Show selected equipment from Run 1. | Explain that available equipment constrains recipe choices. |
| 18-50s | Show exactly three distinct structured recipe cards from real-cloud Run 2. | Explain that the backend returns validated, equipment-compatible choices ranked from the supplied pantry context. |
| 50.05-55.333333s | Show a dedicated transition card, held continuously through the clean 30 fps cut. | "Separate real-cloud E2E run - cooking flow shown next." |
| 55.333333-68.05s | Start the new real-cloud Run 3, advance Microwave Spinach Egg Rice Custard, and request advice from the synthetic cooking fixture. | Explain that the selected recipe becomes a local snapshot and photo advice is observational, not proof of doneness or safety. |
| 68.05-78.05s | Show the full structured advice from the same Run 3. | Call out the observation, two next actions, 15% confidence, and complete safety note. The note is unobscured above bottom navigation. |
| 78.05-83.05s | Continue the same Run 3 cooking flow and show the completion form. | Explain atomic completion and local feedback storage. |
| 83.05-88.05s | Show the saved-success state from the same Run 3. | Confirm that completion was persisted. |
| 88.05-96.05s | Show completed offline history from the same Run 3 without an overlay. | Explain that the recipe snapshot, rating, and completed history remain available locally. |
| 96.05-103.366667s | Keep the same Run 3 offline history visible and add the project-authored repository overlay. | Close on the local history and repository reference. |

The post-transition portion contains only the new Run 3 recording; no Run 1 pixels occur after 55.333333 seconds. Only waiting intervals in Run 3 were shortened. All 60 opening frames and every one of the 225 linearly decoded frames n1485-n1709 were manually inspected at original detail, including exact boundaries n1501/1502, n1659/1660, and n1679/1680. An entire-video alternating/slice/blank scan over all 3101 decoded frames supplemented rather than replaced semantic inspection.

## Final evidence map

- Final media: `.omo/evidence/leftovers/task-12-demo.mp4`
- Media validation: `.omo/evidence/leftovers/task-12-demo-media.json`
- Run and state provenance: `.omo/evidence/leftovers/task-12-demo-capture.json`
- Exact per-run segment and real-cloud provenance: `.omo/evidence/leftovers/task-12-demo-runs.json`
- Fresh retained sanitized real-cloud instrumentation stdout: `.omo/evidence/leftovers/task-12-demo-cloud-proof.txt`
- Eleven logical state bindings: `.omo/evidence/leftovers/task-12-demo-states/`
- Exact opening and transition checks: `.omo/evidence/leftovers/task-12-final-opening-*.png` and `.omo/evidence/leftovers/task-12-final-transition.png`

## Publication checklist

- Use only project UI and the synthetic cooking fixture; do not show personal photos, notifications, credentials, or unrelated device content.
- Preserve the transition card and its narration when making any publication copy. Do not describe the result as a continuous run.
- Watch the exact publication file end to end and keep it below 180 seconds with intelligible audio.
- Run `ffprobe` against the exact publication file. Bind its observed duration, audio stream count, and SHA-256 in `dist/leftovers-demo-media.json`; do not infer values from an editor timeline.
- Upload as Public and verify the YouTube URL in a signed-out browser before recording it in `docs/submission.md`.

The local T12 evidence is not publication proof. The strict schema-1 evidence in `dist/leftovers-demo-media.json`, the bound `dist/leftovers-demo.mp4`, and the public URL remain separate fail-closed gates.
