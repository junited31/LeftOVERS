# /// script
# requires-python = ">=3.14"
# dependencies = ["pydantic==2.13.4"]
# ///

# ─── How to run ───
# 1. Install uv (if not installed):
#      curl -LsSf https://astral.sh/uv/install.sh | sh
# 2. Run directly (no venv, no pip install needed):
#      uv run python -m unittest scripts.tests.test_verify_submission
# 3. Or make executable and run:
#      python -m unittest scripts.tests.test_verify_submission
# ─────────────────

from __future__ import annotations

import hashlib
import json
import tempfile
import subprocess
import sys
import unittest
from dataclasses import replace
from pathlib import Path
from typing import final, override

from scripts.submission_validation import MediaFacts, MediaProbeError, ProbeResponse, PublicUrl, SubmissionRecord
from scripts.verify_submission import parse_record, verify_submission


REQUIRED_MARKERS = (
    "disclosure:pantry",
    "disclosure:equipment",
    "disclosure:preferences",
    "disclosure:cooking-photo",
    "disclosure:store-false",
    "quota:installation-fairness",
    "quota:global-cost-boundary",
    "narrative:codex",
    "narrative:gpt-5.6",
    "instructions:build",
    "instructions:test",
    "license:mit",
)


@final
class FixtureProbe:
    def __init__(self, checksum_body: str | None = None, media_result: MediaFacts | MediaProbeError | None = None) -> None:
        digest = hashlib.sha256(b"local verifier fixture").hexdigest()
        self.checksum_body: str = checksum_body if checksum_body is not None else f"{digest}  app-debug.apk\n"
        self.media_result: MediaFacts | MediaProbeError | None = media_result

    def fetch(self, url: PublicUrl) -> ProbeResponse:
        if url.endswith(".sha256"):
            body = self.checksum_body
        else:
            body = "LeftOVERS Apps for Your Life" if "devpost" in url else "public fixture"
        return ProbeResponse(status_code=200, body=body)

    def inspect_media(self, path: Path) -> MediaFacts | MediaProbeError:
        _ = path
        if self.media_result is not None:
            return self.media_result
        return MediaFacts(duration_seconds=179.0, audio_stream_count=1)


@final
class VerifySubmissionTest(unittest.TestCase):
    @override
    def __init__(self, methodName: str = "runTest") -> None:
        super().__init__(methodName)
        self.temp_dir: tempfile.TemporaryDirectory[str] = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)

    @override
    def setUp(self) -> None:
        self._write_fixture_files()

    @override
    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def _write_fixture_files(self) -> None:
        readme = "# Fixture\n" + "\n".join(f"<!-- {marker} -->" for marker in REQUIRED_MARKERS)
        for relative, text in {
            "README.md": readme,
            "docs/submission.md": "# Submission fixture\n",
            "docs/demo-script.md": "# Demo fixture\n",
            "LICENSE": "MIT License\n",
        }.items():
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            _ = path.write_text(text, encoding="utf-8")
        apk = self.root / "app-debug.apk"
        _ = apk.write_bytes(b"local verifier fixture")
        release = self.root / "app-release.apk"
        _ = release.write_bytes(b"local release fixture")
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        _ = (self.root / "app-debug.apk.sha256").write_text(f"{digest}  app-debug.apk\n", encoding="utf-8")
        release_digest = hashlib.sha256(release.read_bytes()).hexdigest()
        _ = (self.root / "proof.txt").write_text(json.dumps({
            "schema_version": 1,
            "release_apk_sha256": release_digest,
            "manifest_debug_hook_matches": 0,
            "dex_debug_hook_matches": 0,
            "resource_debug_hook_matches": 0,
            "archive_debug_hook_matches": 0,
        }),
            encoding="utf-8",
        )
        media = self.root / "demo.mp4"
        _ = media.write_bytes(b"local narrated demo fixture")
        _ = (self.root / "demo-media.json").write_text(json.dumps({
            "schema_version": 1,
            "media_sha256": hashlib.sha256(media.read_bytes()).hexdigest(),
            "duration_seconds": 179.0,
            "audio_stream_count": 1,
            "probe_tool": "ffprobe",
        }), encoding="utf-8")

    def _complete_record(self) -> SubmissionRecord:
        draft = parse_record(Path("scripts/tests/fixtures/submission_draft.json"))
        return replace(
            draft,
            status="Submitted",
            devpost_url="https://openai.devpost.com/software/leftovers-fixture",
            submitted_at_utc="2026-07-20T12:00:00Z",
            repository_url="https://github.com/junited31/LeftOVERS",
            release_url="https://github.com/junited31/LeftOVERS/releases/tag/v0.1.0-demo",
            apk_url="https://github.com/junited31/LeftOVERS/releases/download/v0.1.0-demo/app-debug.apk",
            checksum_url="https://github.com/junited31/LeftOVERS/releases/download/v0.1.0-demo/app-debug.apk.sha256",
            youtube_url="https://www.youtube.com/watch?v=fixtureVideo1",
            session_id="019f706b-b713-7780-a456-f63ab18b173a",
            apk_path="app-debug.apk",
            checksum_path="app-debug.apk.sha256",
            release_apk_path="app-release.apk",
            release_debug_hook_proof_path="proof.txt",
            demo_media_path="demo.mp4",
            demo_media_evidence_path="demo-media.json",
        )

    def test_draft_reports_exact_required_field_names(self) -> None:
        record = parse_record(Path("scripts/tests/fixtures/submission_draft.json"))

        issues = verify_submission(record, Path.cwd(), FixtureProbe())

        fields = {issue.field for issue in issues}
        self.assertTrue({"status", "devpost_url", "youtube_url", "session_id", "repository_url", "release_url"} <= fields)

    def test_exact_cli_reaches_draft_verification(self) -> None:
        result = subprocess.run(
            (sys.executable, "scripts/verify_submission.py", "--record", "scripts/tests/fixtures/submission_draft.json"),
            cwd=Path.cwd(),
            check=False,
            capture_output=True,
            text=True,
        )

        self.assertEqual(1, result.returncode)
        self.assertIn("[FAIL] status", result.stdout)
        self.assertNotIn("Traceback", result.stderr)

    def test_complete_local_fixture_passes_without_external_claim(self) -> None:
        issues = verify_submission(self._complete_record(), self.root, FixtureProbe())

        self.assertEqual((), issues)

    def test_private_or_example_url_is_rejected(self) -> None:
        record = replace(self._complete_record(), repository_url="https://127.0.0.1/project")

        issues = verify_submission(record, self.root, FixtureProbe())

        self.assertIn("repository_url", {issue.field for issue in issues})

    def test_github_artifact_urls_must_share_repository_owner_and_name(self) -> None:
        record = replace(
            self._complete_record(),
            release_url="https://github.com/other-owner/other-repo/releases/tag/v0.1.0-demo",
        )

        issues = verify_submission(record, self.root, FixtureProbe())

        self.assertIn("release_url", {issue.field for issue in issues})

    def test_remote_checksum_body_must_match_local_debug_apk(self) -> None:
        wrong_checksum = f"{'0' * 64}  app-debug.apk\n"

        issues = verify_submission(self._complete_record(), self.root, FixtureProbe(wrong_checksum))

        self.assertIn("checksum_url", {issue.field for issue in issues})

    def test_three_minute_or_silent_demo_is_rejected(self) -> None:
        record = self._complete_record()
        evidence = self.root / "demo-media.json"
        _ = evidence.write_text(json.dumps({
            "schema_version": 1,
            "media_sha256": hashlib.sha256((self.root / "demo.mp4").read_bytes()).hexdigest(),
            "duration_seconds": 180.0,
            "audio_stream_count": 0,
            "probe_tool": "ffprobe",
        }), encoding="utf-8")

        issues = verify_submission(record, self.root, FixtureProbe())

        self.assertIn("demo_media_evidence_path", {issue.field for issue in issues})

    def test_demo_requires_sha_bound_media_evidence(self) -> None:
        record = replace(
            self._complete_record(),
            demo_media_path="missing-demo.mp4",
            demo_media_evidence_path="missing-demo-media.json",
        )

        issues = verify_submission(record, self.root, FixtureProbe())

        self.assertTrue({"demo_media_path", "demo_media_evidence_path"} <= {issue.field for issue in issues})

    def test_demo_media_changes_after_probe_are_rejected(self) -> None:
        _ = (self.root / "demo.mp4").write_bytes(b"changed demo bytes")

        issues = verify_submission(self._complete_record(), self.root, FixtureProbe())

        self.assertIn("demo_media_evidence_path", {issue.field for issue in issues})

    def test_matching_hand_written_claims_do_not_override_failed_media_probe(self) -> None:
        issues = verify_submission(self._complete_record(), self.root, FixtureProbe(media_result=MediaProbeError("invalid media")))

        self.assertIn("demo_media_evidence_path", {issue.field for issue in issues})

    def test_observed_long_silent_or_mismatched_media_fails_closed(self) -> None:
        for facts in (MediaFacts(180.0, 1), MediaFacts(179.0, 0), MediaFacts(178.0, 1)):
            with self.subTest(facts=facts):
                issues = verify_submission(self._complete_record(), self.root, FixtureProbe(media_result=facts))
                self.assertIn("demo_media_evidence_path", {issue.field for issue in issues})

    def test_impossible_utc_timestamp_is_rejected(self) -> None:
        record = replace(self._complete_record(), submitted_at_utc="2026-99-99T99:99:99Z")

        issues = verify_submission(record, self.root, FixtureProbe())

        self.assertIn("submitted_at_utc", {issue.field for issue in issues})

    def test_missing_apk_checksum_and_release_proof_are_rejected(self) -> None:
        record = replace(
            self._complete_record(),
            apk_path="missing.apk",
            checksum_path="missing.sha256",
            release_debug_hook_proof_path="missing-proof.txt",
        )

        issues = verify_submission(record, self.root, FixtureProbe())

        self.assertTrue({"apk_path", "checksum_path", "release_debug_hook_proof_path"} <= {issue.field for issue in issues})

    def test_non_200_health_is_rejected(self) -> None:
        class UnhealthyProbe:
            def fetch(self, url: PublicUrl) -> ProbeResponse:
                return ProbeResponse(status_code=503 if url.endswith("/health") else 200, body="LeftOVERS Apps for Your Life")

            def inspect_media(self, path: Path) -> MediaFacts:
                _ = path
                return MediaFacts(duration_seconds=179.0, audio_stream_count=1)

        issues = verify_submission(self._complete_record(), self.root, UnhealthyProbe())

        self.assertIn("backend_health_url", {issue.field for issue in issues})

    def test_internal_backend_health_host_is_rejected(self) -> None:
        record = replace(self._complete_record(), backend_health_url="https://leftovers-api.internal/health")

        issues = verify_submission(record, self.root, FixtureProbe())

        self.assertIn("backend_health_url", {issue.field for issue in issues})

    def test_missing_machine_readable_disclosure_is_rejected(self) -> None:
        readme = self.root / "README.md"
        _ = readme.write_text(readme.read_text(encoding="utf-8").replace("<!-- disclosure:equipment -->", ""), encoding="utf-8")

        issues = verify_submission(self._complete_record(), self.root, FixtureProbe())

        self.assertIn("README.disclosure:equipment", {issue.field for issue in issues})

    def test_strict_zero_count_release_proof_is_accepted(self) -> None:
        digest = hashlib.sha256((self.root / "app-release.apk").read_bytes()).hexdigest()
        _ = (self.root / "proof.txt").write_text(json.dumps({
            "schema_version": 1,
            "release_apk_sha256": digest,
            "manifest_debug_hook_matches": 0,
            "dex_debug_hook_matches": 0,
            "resource_debug_hook_matches": 0,
            "archive_debug_hook_matches": 0,
        }), encoding="utf-8")

        issues = verify_submission(self._complete_record(), self.root, FixtureProbe())

        self.assertNotIn("release_debug_hook_proof_path", {issue.field for issue in issues})

    def test_dirty_git_worktree_is_rejected(self) -> None:
        result = subprocess.run(("git", "init", "--quiet", str(self.root)), check=False)
        self.assertEqual(0, result.returncode)

        issues = verify_submission(self._complete_record(), self.root, FixtureProbe())

        self.assertIn("git_worktree", {issue.field for issue in issues})

    def test_release_proof_is_rejected_after_apk_changes(self) -> None:
        _ = (self.root / "app-release.apk").write_bytes(b"new release bytes")

        issues = verify_submission(self._complete_record(), self.root, FixtureProbe())

        self.assertIn("release_apk_path", {issue.field for issue in issues})

    def test_contradictory_positive_release_hook_counts_are_rejected(self) -> None:
        digest = hashlib.sha256((self.root / "app-release.apk").read_bytes()).hexdigest()
        _ = (self.root / "proof.txt").write_text(
            f'{{"legacy":"SHA-256: {digest} DEBUG_SEED 1 match DEBUG_RESET 1 match 0 debug hook matches"}}',
            encoding="utf-8",
        )

        issues = verify_submission(self._complete_record(), self.root, FixtureProbe())

        self.assertIn("release_debug_hook_proof_path", {issue.field for issue in issues})


if __name__ == "__main__":
    _ = unittest.main()
