# Asset and fixture provenance

LeftOVERS does not ship a recipe corpus, stock photography, third-party food images, custom fonts, or downloaded brand artwork.

| Asset or data | Origin | Rights and use |
|---|---|---|
| `android/app/src/main/res/drawable/ic_launcher.xml` | Original vector launcher mark authored for this repository | Covered by the repository MIT license |
| Debug pantry and equipment sample | Original synthetic names, quantities, dates, and equipment IDs in `DebugSeedReceiver.kt` | Test/demo data; no personal or licensed dataset source |
| Recipe and advice examples | Generated during authenticated GPT-5.6 test/demo requests, then validated as structured output | Demonstration output only; no scraped or bundled recipe corpus |
| Cooking-photo fixtures | Solid-color bitmaps generated in Android instrumentation or deployment preflight code | Synthetic pixels; no person, kitchen, trademark, or third-party image |
| UI screenshots under `.omo/evidence/leftovers/` | Captured from the LeftOVERS app on a test device/emulator | Project-authored UI and synthetic/demo data only |
| T12 demo transition card | Generated locally with Windows `System.Drawing`; text is “Separate real-cloud E2E run — cooking flow shown next.” | Project-authored disclosure card; no third-party visual asset |
| T12 demo narration | Generated locally with Windows `System.Speech` using the installed Microsoft Zira Desktop voice | Project-authored script rendered by an operating-system voice; no music or third-party recording |
| T12 final physical-device captures | Exactly three fresh physical-device capture runs: Run 1 used a local MockWebServer fixture for pantry and equipment only; Run 2 exercised the deployed real cloud for recipe cards; Run 3 is one new deployed-real-cloud Microwave Spinach Egg Rice Custard recording covering cooking, full advice, completion, success, offline history, and the repository close; exact per-run segment hashes and fresh sanitized raw instrumentation proof are in `task-12-demo-runs.json` and `task-12-demo-cloud-proof.txt` | Project-authored UI and synthetic fixture pixels only; the repository overlay is project-authored; no Run 1 pixels appear after the disclosed boundary and no continuous-run claim is made |
| Material icons | AndroidX Compose Material Icons dependency | Used under the dependency's Apache License 2.0 terms |

The narrated publication demo should use only the synthetic cooking fixture, project UI, and documented transition/narration assets. If any new screenshot, audio, music, image, or font is added later, record its creator, source URL, license, modifications, and exact files here before publication.
