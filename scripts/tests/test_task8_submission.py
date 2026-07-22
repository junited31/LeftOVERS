from __future__ import annotations

import copy
import hashlib
import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, final, override

from pydantic import ValidationError

import scripts.submission_validation as validation


SOURCE_SHA = "1" * 40
APK_SHA = "2" * 64
RUN_ID = "12345678-1234-4234-8234-123456789abc"
SERIAL = "fixture-serial"
MODEL = "fixture-model"
START = "2026-07-22T00:00:00Z"
END = "2026-07-22T00:05:00Z"
TASK_RELATIVE = ".omo/evidence/leftovers-expansion/task-12"
PROJECT_ROOT = Path(__file__).parents[2]
ADDENDUM_SHA256 = "bb75d40fa27c4466b9fc536f454cc6b23d056532f551c8194ece0a1131c6dcce"
STATE_IDS = (
    "01-clear-app-data",
    "02-english-equipment-onboarding",
    "03-suggested-pantry",
    "04-onion-quarter-half",
    "05-restart-persistence",
    "06-switch-korean",
    "07-select-recipe-kind",
    "08-exactly-three-recipes",
    "09-cooking-photo-advice",
    "10-final-photo-completion-history",
    "11-english-restart",
)
CAPTURE_IDS = tuple(
    ("04a-onion-quarter", "04b-onion-half") if step == 4 else (state_id,)
    for step, state_id in enumerate(STATE_IDS, 1)
)
MARKERS = {
    "01-clear-app-data": ("Select the equipment in your kitchen",),
    "02-english-equipment-onboarding": ("Select the equipment in your kitchen", "Continue"),
    "03-suggested-pantry": ("Ingredients available now", "Onion"),
    "04a-onion-quarter": ("Onion", "0.25"),
    "04b-onion-half": ("Onion", "0.5"),
    "05-restart-persistence": ("Ingredients available now", "Onion", "0.5"),
    "06-switch-korean": ("설정", "언어", "한국어"),
    "07-select-recipe-kind": ("레시피 종류", "식사"),
    "08-exactly-three-recipes": ("냉장고 재료로 만드는 세 가지 요리", "식사"),
    "09-cooking-photo-advice": ("사진 조언", "관찰 결과", "안전 안내"),
    "10-final-photo-completion-history": ("완료한 요리", "완성 사진"),
    "11-english-restart": ("Completed meals", "Final photo"),
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def png(width: int, height: int, value: int) -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    row = b"\0" + bytes((value, value ^ 0x55, value ^ 0xAA, 255)) * width
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(row * height)) + chunk(b"IEND", b"")


def forged_interlaced_png(width: int, height: int) -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 1)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(b"\0")) + chunk(b"IEND", b"")


def git(root: Path, *args: str) -> str:
    result = subprocess.run(("git", *args), cwd=root, check=True, capture_output=True, text=True, encoding="utf-8")
    return result.stdout.strip()


def submission_record(source_sha: str = SOURCE_SHA) -> dict[str, Any]:
    return {
        "schema_version": 2,
        "publication_source_sha": source_sha,
        "status": "Draft",
        "devpost_url": "",
        "submitted_at_utc": "",
        "repository_url": "https://github.com/junited31/LeftOVERS",
        "release_url": "",
        "apk_url": "",
        "checksum_url": "",
        "backend_health_url": "https://leftovers-api.example.run.app/health",
        "youtube_url": "",
        "session_id": "",
        "apk_path": "android/app/build/outputs/apk/debug/app-debug.apk",
        "checksum_path": "dist/app-debug.apk.sha256",
        "release_apk_path": "android/app/build/outputs/apk/release/app-release-unsigned.apk",
        "release_debug_hook_proof_path": "dist/release-debug-hooks.json",
        "demo_media_path": "dist/leftovers-demo.mp4",
        "demo_media_evidence_path": "dist/leftovers-demo-media.json",
        "readme_path": "README.md",
        "submission_path": "docs/submission.md",
        "demo_script_path": "docs/demo-script.md",
        "license_path": "LICENSE",
    }


def release_record(source_sha: str = SOURCE_SHA) -> dict[str, Any]:
    return {
        "schema_version": 2,
        "publication_source_sha": source_sha,
        "release_apk_sha256": "3" * 64,
        "manifest_debug_hook_matches": 0,
        "dex_debug_hook_matches": 0,
        "resource_debug_hook_matches": 0,
        "archive_debug_hook_matches": 0,
    }


def media_record(source_sha: str = SOURCE_SHA) -> dict[str, Any]:
    return {
        "schema_version": 2,
        "publication_source_sha": source_sha,
        "media_sha256": "4" * 64,
        "duration_seconds": 10.0,
        "audio_stream_count": 1,
        "probe_tool": "ffprobe",
    }


@final
class ExpansionFixture:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.task_root = root / TASK_RELATIVE
        self.source_sha = SOURCE_SHA
        self.task_root.mkdir(parents=True)
        self.manifest = self._manifest()
        self.write_visual()
        for name in ("manual.json", "failure-stale.json", "failure-restart.json", "cleanup.json"):
            (self.task_root / name).write_text("{}\n", encoding="utf-8", newline="\n")
        (self.task_root / "submission-draft.json").write_text(
            json.dumps(submission_record(), ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        apk_path = root / "android/app/build/outputs/apk/debug/app-debug.apk"
        apk_path.parent.mkdir(parents=True)
        apk_bytes = b"fixture apk"
        apk_path.write_bytes(apk_bytes)
        self.apk_sha = sha256(apk_bytes)
        self._replace_binding("apk_sha256", self.apk_sha)
        self.write_visual()
        (self.task_root / "apk-reference.json").write_text(
            json.dumps(
                {
                    "schema_version": 2,
                    "publication_source_sha": SOURCE_SHA,
                    "apk_path": "android/app/build/outputs/apk/debug/app-debug.apk",
                    "apk_sha256": self.apk_sha,
                },
                ensure_ascii=False,
                indent=2,
            )
            + "\n",
            encoding="utf-8",
            newline="\n",
        )
        self.write_artifact()

    def _replace_binding(self, field: str, value: str) -> None:
        self.manifest[field] = value
        for state in self.manifest["states"]:
            state[field] = value
            for capture in state["captures"]:
                capture[field] = value

    def bind_source(self, source_sha: str) -> None:
        self.source_sha = source_sha
        self._replace_binding("publication_source_sha", source_sha)
        submission = submission_record(source_sha)
        (self.task_root / "submission-draft.json").write_text(
            json.dumps(submission, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        reference_path = self.task_root / "apk-reference.json"
        reference = json.loads(reference_path.read_text(encoding="utf-8"))
        reference["publication_source_sha"] = source_sha
        reference_path.write_text(json.dumps(reference, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
        self.write_visual()
        self.write_artifact()

    def _manifest(self) -> dict[str, Any]:
        states: list[dict[str, Any]] = []
        moment = datetime.fromisoformat(START.replace("Z", "+00:00"))
        asset_index = 0
        for step, (state_id, capture_ids) in enumerate(zip(STATE_IDS, CAPTURE_IDS, strict=True), 1):
            locale = "en" if step <= 5 or step == 11 else "ko"
            captures: list[dict[str, Any]] = []
            for capture_id in capture_ids:
                asset_index += 1
                basename = capture_id
                png_bytes = png(1, 1, asset_index)
                xml_bytes = (
                    '<hierarchy rotation="0"><node text="'
                    + " | ".join(MARKERS[capture_id])
                    + '" /></hierarchy>\n'
                ).encode("utf-8")
                png_path = self.task_root / f"{basename}.png"
                xml_path = self.task_root / f"{basename}.xml"
                png_path.write_bytes(png_bytes)
                xml_path.write_bytes(xml_bytes)
                captures.append(
                    {
                        "capture_id": capture_id,
                        "state_id": state_id,
                        "locale": locale,
                        "publication_source_sha": SOURCE_SHA,
                        "apk_sha256": APK_SHA,
                        "device_serial": SERIAL,
                        "device_model": MODEL,
                        "capture_run_uuid": RUN_ID,
                        "captured_at_utc": (moment + timedelta(seconds=asset_index)).isoformat().replace("+00:00", "Z"),
                        "png_path": f"{TASK_RELATIVE}/{basename}.png",
                        "png_sha256": sha256(png_bytes),
                        "png_width": 1,
                        "png_height": 1,
                        "xml_path": f"{TASK_RELATIVE}/{basename}.xml",
                        "xml_sha256": sha256(xml_bytes),
                    }
                )
            states.append(
                {
                    "step_number": step,
                    "state_id": state_id,
                    "locale": locale,
                    "publication_source_sha": SOURCE_SHA,
                    "apk_sha256": APK_SHA,
                    "device_serial": SERIAL,
                    "device_model": MODEL,
                    "capture_run_uuid": RUN_ID,
                    "captures": captures,
                }
            )
        return {
            "schema_version": 2,
            "publication_source_sha": SOURCE_SHA,
            "apk_sha256": APK_SHA,
            "device_serial": SERIAL,
            "device_model": MODEL,
            "capture_run_uuid": RUN_ID,
            "run_started_at_utc": START,
            "run_finished_at_utc": END,
            "states": states,
        }

    def write_visual(self) -> None:
        (self.task_root / "visual-manifest.json").write_text(
            json.dumps(self.manifest, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
            newline="\n",
        )

    def expected_artifact_paths(self) -> list[str]:
        paths = {
            f"{TASK_RELATIVE}/{name}"
            for name in (
                "submission-draft.json",
                "visual-manifest.json",
                "manual.json",
                "failure-stale.json",
                "failure-restart.json",
                "cleanup.json",
                "apk-reference.json",
            )
        }
        for state in self.manifest["states"]:
            for capture in state["captures"]:
                paths.update((capture["png_path"], capture["xml_path"]))
        return sorted(paths)

    def write_artifact(self, transform: Any = None, canonical: bool = True) -> bytes:
        files = [
            {"path": path, "sha256": sha256((self.root / path).read_bytes())}
            for path in self.expected_artifact_paths()
        ]
        record: dict[str, Any] = {"schema_version": 2, "publication_source_sha": self.source_sha, "files": files}
        if transform is not None:
            transform(record)
        if canonical:
            data = (
                json.dumps(record, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":")) + "\n"
            ).encode("utf-8")
        else:
            data = (json.dumps(record, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        (self.task_root / "artifact-manifest.json").write_bytes(data)
        return data

    def visual_issues(self, **expected: str) -> tuple[Any, ...]:
        values = {
            "approved_source_sha": SOURCE_SHA,
            "apk_sha256": self.apk_sha,
            "device_serial": SERIAL,
            "device_model": MODEL,
            "run_started_at_utc": START,
            "run_finished_at_utc": END,
        }
        values.update(expected)
        return validation.validate_expansion_visual_evidence(self.root, self.task_root, **values)

    def artifact_result(self) -> Any:
        return validation.validate_expansion_artifact_manifest(self.root, self.task_root, self.source_sha, self.apk_sha)


@final
class Task8RecordAndSourceTest(unittest.TestCase):
    def test_all_publication_records_are_strict_v2_without_string_coercion(self) -> None:
        rows = (
            (validation.SubmissionRecord, submission_record()),
            (validation.ReleaseProof, release_record()),
            (validation.MediaEvidence, media_record()),
        )
        for model, valid in rows:
            with self.subTest(model=model.__name__, row="valid"):
                self.assertEqual(SOURCE_SHA, model.model_validate(valid).publication_source_sha)
            for row, mutate in (
                ("missing-version", lambda value: value.pop("schema_version")),
                ("missing-source-sha", lambda value: value.pop("publication_source_sha")),
                ("v1", lambda value: value.update(schema_version=1)),
                ("float-version", lambda value: value.update(schema_version=2.0)),
                ("extra", lambda value: value.update(unexpected="no")),
                ("noncanonical-sha", lambda value: value.update(publication_source_sha="A" * 40)),
            ):
                candidate = copy.deepcopy(valid)
                mutate(candidate)
                with self.subTest(model=model.__name__, row=row), self.assertRaises(ValidationError):
                    model.model_validate(candidate)
        proof = release_record()
        for field in (
            "manifest_debug_hook_matches",
            "dex_debug_hook_matches",
            "resource_debug_hook_matches",
            "archive_debug_hook_matches",
        ):
            candidate = copy.deepcopy(proof)
            candidate[field] = False
            with self.subTest(model="ReleaseProof", row="boolean-zero", field=field), self.assertRaises(ValidationError):
                validation.ReleaseProof.model_validate(candidate)
            for scalar in (123, b"1" * 40, True):
                candidate = copy.deepcopy(valid)
                candidate["publication_source_sha"] = scalar
                with self.subTest(model=model.__name__, row="coerced-string", scalar=type(scalar).__name__), self.assertRaises(ValidationError):
                    model.model_validate(candidate)

    def _repo(self) -> tuple[tempfile.TemporaryDirectory[str], Path, str]:
        temp = tempfile.TemporaryDirectory()
        root = Path(temp.name)
        git(root, "init", "--quiet")
        git(root, "config", "user.email", "fixture@example.invalid")
        git(root, "config", "user.name", "Fixture")
        for relative in ("app.txt", "README.md", "scripts/submission_validation.py"):
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"source:{relative}\n", encoding="utf-8")
        git(root, "add", "app.txt", "README.md", "scripts/submission_validation.py")
        git(root, "commit", "--quiet", "-m", "source")
        return temp, root, git(root, "rev-parse", "HEAD")

    def _records(self, source: str) -> tuple[Any, Any, Any]:
        return (
            validation.SubmissionRecord.model_validate(submission_record(source)),
            validation.ReleaseProof.model_validate(release_record(source)),
            validation.MediaEvidence.model_validate(media_record(source)),
        )

    def test_publication_source_accepts_only_exact_post_source_allowlist(self) -> None:
        temp, root, source = self._repo()
        self.addCleanup(temp.cleanup)
        allowed = (
            ".omo/evidence/leftovers-expansion/task-8/proof.json",
            ".omo/evidence/leftovers/task-12-publication/proof.json",
            ".omo/start-work/ledger.jsonl",
            ".omo/boulder.json",
            ".omo/plans/leftovers.md",
            "dist/app-debug.apk.sha256",
            "dist/release-debug-hooks.json",
            "docs/submission.md",
            "docs/demo-script.md",
            "docs/asset-provenance.md",
        )
        for relative in allowed:
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("allowed\n", encoding="utf-8")
        git(root, "add", *allowed)
        git(root, "commit", "--quiet", "-m", "allowed evidence")

        issues = validation.validate_publication_source(self._records(source), root)

        self.assertEqual((), issues)

    def test_source_missing_nonancestor_mismatch_and_dirty_tree_fail_closed(self) -> None:
        temp, root, source = self._repo()
        self.addCleanup(temp.cleanup)
        records = self._records(source)
        side = git(root, "commit-tree", "HEAD^{tree}", "-m", "side")
        rows = (
            ("missing", self._records("f" * 40), "publication_source_sha"),
            ("nonancestor", self._records(side), "publication_source_sha"),
            ("mismatch", (records[0], records[1], validation.MediaEvidence.model_validate(media_record("e" * 40))), "publication_source_sha"),
        )
        for name, candidate, field in rows:
            with self.subTest(name=name):
                issues = validation.validate_publication_source(candidate, root)
                self.assertIn(field, {issue.field for issue in issues})
        (root / "dirty.txt").write_text("dirty\n", encoding="utf-8")
        issues = validation.validate_publication_source(records, root)
        self.assertIn("git_worktree", {issue.field for issue in issues})

    def test_every_forbidden_drift_is_named_even_with_simultaneous_allowed_evidence(self) -> None:
        temp, root, source = self._repo()
        self.addCleanup(temp.cleanup)
        forbidden = (
            "android/source.kt",
            "backend/source.py",
            "README.md",
            "scripts/submission_validation.py",
            ".omo/plans/leftovers-expansion.md",
            ".omo/drafts/leftovers-expansion.md",
            ".omo/unnamed.json",
            ".omo/evidence/leftovers-expansion-lookalike/proof.json",
            ".omo/evidence/leftovers/task-12-publication-lookalike/proof.json",
            "dist/unnamed.json",
            "scripts/tests/fixtures/submission_draft.json",
        )
        allowed = ".omo/evidence/leftovers-expansion/task-8/allowed.json"
        for relative in (*forbidden, allowed):
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"changed:{relative}\n", encoding="utf-8")
        git(root, "add", *forbidden, allowed)
        git(root, "commit", "--quiet", "-m", "mixed drift")

        issues = validation.validate_publication_source(self._records(source), root)
        fields = {issue.field for issue in issues}

        for relative in forbidden:
            with self.subTest(relative=relative):
                self.assertIn(f"publication_source_sha.{relative}", fields)
        self.assertNotIn(f"publication_source_sha.{allowed}", fields)

    def test_rename_from_forbidden_source_into_allowed_evidence_cannot_hide_deletion(self) -> None:
        temp, root, source = self._repo()
        self.addCleanup(temp.cleanup)
        allowed = root / ".omo/evidence/leftovers-expansion/task-8/renamed-readme.md"
        allowed.parent.mkdir(parents=True)
        (root / "README.md").rename(allowed)
        git(root, "add", "README.md", ".omo/evidence/leftovers-expansion/task-8/renamed-readme.md")
        git(root, "commit", "--quiet", "-m", "rename attack")

        issues = validation.validate_publication_source(self._records(source), root)

        self.assertIn("publication_source_sha.README.md", {issue.field for issue in issues})


@final
class Task8DocsContractTest(unittest.TestCase):
    def test_active_docs_name_vertex_runtime_and_preserve_openai_only_as_history(self) -> None:
        paths = (
            PROJECT_ROOT / "README.md",
            PROJECT_ROOT / "docs/submission.md",
            PROJECT_ROOT / "docs/demo-script.md",
            PROJECT_ROOT / "docs/asset-provenance.md",
        )
        text = "\n".join(path.read_text(encoding="utf-8") for path in paths)
        for stale_claim in (
            "The backend uses the OpenAI Responses API",
            "calls the OpenAI Responses API with GPT-5.6",
            "Production configuration uses `OPENAI_API_KEY`",
            "strict schema-1",
        ):
            self.assertNotIn(stale_claim, text)
        for required in ("Vertex", "ADC", "historical", "GPT-5.6", "schema-v2", "historical-only"):
            self.assertIn(required, text)

    def test_privacy_doc_keeps_application_and_provider_controls_distinct(self) -> None:
        text = (PROJECT_ROOT / "docs/privacy.md").read_text(encoding="utf-8")
        for required in (
            "app-private local storage",
            "does not write an application-owned copy",
            "metadata",
            "Firestore stores only HMAC-derived",
            "billable",
            "best effort",
            "does not claim provider Zero Data Retention",
            "not, by itself, a training opt-out",
            "separately isolated project",
            "cannot verify doneness",
            "https://docs.cloud.google.com/docs/authentication/application-default-credentials",
            "https://docs.cloud.google.com/gemini-enterprise-agent-platform/resources/zero-data-retention",
            "https://developers.openai.com/api/docs/guides/your-data",
        ):
            self.assertIn(required, text)

    def test_addendum_exact_hash_and_all_future_active_areas_are_locked(self) -> None:
        path = PROJECT_ROOT / ".omo/evidence/leftovers-expansion/task-8/superseding-vertex-publication-addendum.md"
        data = path.read_bytes()
        text = data.decode("utf-8")
        self.assertEqual(ADDENDUM_SHA256, sha256(data))
        for heading in (
            "### TLDR for humans",
            "### TLDR for machines",
            "### Remaining scope and guardrails",
            "### Verification, source, and recapture rules",
            "### Superseding original T12 publication flow",
            "### T12 acceptance, QA, cleanup, and authority",
            "### Superseding final verification",
            "### Superseding success criteria",
        ):
            self.assertIn(heading, text)
        for required in (
            "must not edit, delete, reword, re-check, or otherwise change any completed T1-T11 row",
            "truthful historical project history",
            "Vertex AI Gemini",
            "three actual strict schema-v2 records",
            "recapture",
            "YouTube Public upload",
            "Devpost Submit",
            "action-time human confirmation",
        ):
            self.assertIn(required, text)


@final
class Task8VisualAndArtifactTest(unittest.TestCase):
    @override
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.fixture = ExpansionFixture(self.root)

    @override
    def tearDown(self) -> None:
        self.temp.cleanup()

    def assertVisualField(self, field: str) -> None:
        self.assertIn(field, {issue.field for issue in self.fixture.visual_issues()})

    def test_complete_canonical_eleven_state_fixture_passes(self) -> None:
        self.assertEqual((), self.fixture.visual_issues())

    def test_production_command_validates_source_visual_closure_and_returns_digest(self) -> None:
        git(self.root, "init", "--quiet")
        git(self.root, "config", "user.email", "fixture@example.invalid")
        git(self.root, "config", "user.name", "Fixture")
        (self.root / ".gitignore").write_text("android/app/build/\n", encoding="utf-8")
        (self.root / "source.txt").write_text("source\n", encoding="utf-8")
        git(self.root, "add", ".gitignore", "source.txt")
        git(self.root, "commit", "--quiet", "-m", "source")
        source = git(self.root, "rev-parse", "HEAD")
        self.fixture.bind_source(source)
        git(self.root, "add", ".omo/evidence/leftovers-expansion/task-12")
        git(self.root, "commit", "--quiet", "-m", "evidence")
        expected_digest = sha256((self.fixture.task_root / "artifact-manifest.json").read_bytes())
        script = Path(__file__).parents[1] / "verify_submission.py"

        result = subprocess.run(
            (
                sys.executable,
                str(script),
                "--expansion-evidence",
                TASK_RELATIVE,
                "--source-sha",
                source,
                "--apk-sha",
                self.fixture.apk_sha,
                "--device-serial",
                SERIAL,
                "--device-model",
                MODEL,
                "--run-start",
                START,
                "--run-end",
                END,
            ),
            cwd=self.root,
            check=False,
            capture_output=True,
            text=True,
        )

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(f"artifact_record_digest={expected_digest}", result.stdout)

    def test_missing_extra_reordered_and_duplicate_steps_fail_with_state_path(self) -> None:
        original = copy.deepcopy(self.fixture.manifest["states"])
        rows = (
            ("missing", original[:-1]),
            ("extra", original + [copy.deepcopy(original[-1])]),
            ("reordered", [original[1], original[0], *original[2:]]),
            ("duplicate", [original[0], original[0], *original[2:]]),
        )
        for name, states in rows:
            with self.subTest(name=name):
                self.fixture.manifest["states"] = copy.deepcopy(states)
                self.fixture.write_visual()
                self.assertVisualField("visual-manifest.states")
        self.fixture.manifest["states"] = original

    def test_step_four_missing_reversed_extra_and_duplicate_subcapture_fail(self) -> None:
        original = copy.deepcopy(self.fixture.manifest["states"][3]["captures"])
        rows = (
            ("missing", original[:1]),
            ("reversed", list(reversed(original))),
            ("extra", original + [copy.deepcopy(original[-1])]),
            ("duplicate", [original[0], copy.deepcopy(original[0])]),
        )
        for name, captures in rows:
            with self.subTest(name=name):
                self.fixture.manifest["states"][3]["captures"] = copy.deepcopy(captures)
                self.fixture.write_visual()
                self.assertVisualField("visual-manifest.states[4].captures")
        self.fixture.manifest["states"][3]["captures"] = original

    def test_unsafe_duplicate_casefold_and_symlink_paths_fail(self) -> None:
        capture = self.fixture.manifest["states"][0]["captures"][0]
        original = capture["png_path"]
        unsafe = ("", "/absolute.png", "C:/drive.png", "//server/share.png", "a\\b.png", "a//b.png", "a/./b.png", "a/../b.png", "a/file.png:stream")
        for path in unsafe:
            with self.subTest(path=path):
                capture["png_path"] = path
                self.fixture.write_visual()
                self.assertVisualField("visual-manifest.states[1].captures[1].png_path")
        capture["png_path"] = self.fixture.manifest["states"][1]["captures"][0]["png_path"].upper()
        self.fixture.write_visual()
        self.assertVisualField("visual-manifest.states[2].captures[1].png_path")
        capture["png_path"] = original
        outside = self.root / "outside"
        outside.mkdir()
        outside_asset = outside / "asset.png"
        outside_asset.write_bytes(png(1, 1, 99))
        link = self.fixture.task_root / "linked"
        if os.name == "nt":
            result = subprocess.run(("cmd", "/c", "mklink", "/J", str(link), str(outside)), check=False, capture_output=True)
            self.assertEqual(0, result.returncode)
        else:
            os.symlink(outside, link, target_is_directory=True)
        capture["png_path"] = f"{TASK_RELATIVE}/linked/asset.png"
        capture["png_sha256"] = sha256(outside_asset.read_bytes())
        self.fixture.write_visual()
        self.assertVisualField("visual-manifest.states[1].captures[1].png_path")

    def test_windows_junction_inside_evidence_root_is_still_forbidden(self) -> None:
        capture = self.fixture.manifest["states"][0]["captures"][0]
        target = self.fixture.task_root / "junction-target"
        target.mkdir()
        target_asset = target / "asset.png"
        target_asset.write_bytes(png(1, 1, 101))
        link = self.fixture.task_root / "junction-link"
        if os.name == "nt":
            result = subprocess.run(("cmd", "/c", "mklink", "/J", str(link), str(target)), check=False, capture_output=True)
            self.assertEqual(0, result.returncode)
        else:
            os.symlink(target, link, target_is_directory=True)
        capture["png_path"] = f"{TASK_RELATIVE}/junction-link/asset.png"
        capture["png_sha256"] = sha256(target_asset.read_bytes())
        self.fixture.write_visual()

        self.assertVisualField("visual-manifest.states[1].captures[1].png_path")

    def test_absent_png_and_xml_each_fail_at_typed_path(self) -> None:
        capture = self.fixture.manifest["states"][0]["captures"][0]
        for field in ("png_path", "xml_path"):
            path = self.root / capture[field]
            data = path.read_bytes()
            path.unlink()
            with self.subTest(field=field):
                self.assertVisualField(f"visual-manifest.states[1].captures[1].{field}")
            path.write_bytes(data)

    def test_png_signature_ihdr_dimensions_hash_and_reused_hash_fail(self) -> None:
        capture = self.fixture.manifest["states"][0]["captures"][0]
        path = self.root / capture["png_path"]
        original = path.read_bytes()
        rows = (
            ("signature", b"not-png", "png_path"),
            ("ihdr", original[:12] + b"BAD!" + original[16:], "png_path"),
            ("forged-adam7", forged_interlaced_png(100, 100), "png_path"),
            ("dimensions", png(2, 1, 1), "png_width"),
            ("hash", original + b"x", "png_sha256"),
        )
        for name, data, field in rows:
            with self.subTest(name=name):
                path.write_bytes(data)
                if name != "hash":
                    capture["png_sha256"] = sha256(data)
                self.fixture.write_visual()
                self.assertVisualField(f"visual-manifest.states[1].captures[1].{field}")
                capture["png_sha256"] = sha256(original)
        path.write_bytes(original)
        second = self.fixture.manifest["states"][1]["captures"][0]
        second_path = self.root / second["png_path"]
        second_path.write_bytes(original)
        second["png_sha256"] = sha256(original)
        self.fixture.write_visual()
        self.assertVisualField("visual-manifest.states[2].captures[1].png_sha256")

    def test_xml_malformed_empty_wrong_state_hash_and_reused_hash_fail(self) -> None:
        capture = self.fixture.manifest["states"][0]["captures"][0]
        path = self.root / capture["xml_path"]
        original = path.read_bytes()
        rows = (
            ("malformed", b"<hierarchy>", "xml_path", True),
            ("empty", b"<hierarchy />\n", "xml_path", True),
            ("doctype", b'<!DOCTYPE hierarchy [<!ENTITY x "marker">]><hierarchy><node text="&x;" /></hierarchy>\n', "xml_path", True),
            ("utf16-doctype", ('<?xml version="1.0" encoding="UTF-16"?><!DOCTYPE hierarchy [<!ENTITY x "Select the equipment in your kitchen">]><hierarchy><node text="&x;" /></hierarchy>').encode("utf-16"), "xml_path", True),
            ("wrong-state", b'<hierarchy><node text="Completed meals | Final photo" /></hierarchy>\n', "xml_path", True),
            ("hash", original + b"x", "xml_sha256", False),
        )
        for name, data, field, update_hash in rows:
            with self.subTest(name=name):
                path.write_bytes(data)
                if update_hash:
                    capture["xml_sha256"] = sha256(data)
                self.fixture.write_visual()
                self.assertVisualField(f"visual-manifest.states[1].captures[1].{field}")
                capture["xml_sha256"] = sha256(original)
        path.write_bytes(original)
        second = self.fixture.manifest["states"][1]["captures"][0]
        second_path = self.root / second["xml_path"]
        second_path.write_bytes(original)
        second["xml_sha256"] = sha256(original)
        self.fixture.write_visual()
        self.assertVisualField("visual-manifest.states[2].captures[1].xml_sha256")

    def test_every_binding_locale_run_and_timestamp_mismatch_fails(self) -> None:
        capture = self.fixture.manifest["states"][5]["captures"][0]
        state = self.fixture.manifest["states"][5]
        rows = (
            (self.fixture.manifest, "publication_source_sha", "f" * 40, "visual-manifest.publication_source_sha"),
            (state, "publication_source_sha", "f" * 40, "visual-manifest.states[6].publication_source_sha"),
            (capture, "publication_source_sha", "f" * 40, "visual-manifest.states[6].captures[1].publication_source_sha"),
            (capture, "apk_sha256", "f" * 64, "visual-manifest.states[6].captures[1].apk_sha256"),
            (capture, "device_serial", "wrong", "visual-manifest.states[6].captures[1].device_serial"),
            (capture, "device_model", "wrong", "visual-manifest.states[6].captures[1].device_model"),
            (capture, "locale", "en", "visual-manifest.states[6].captures[1].locale"),
            (capture, "capture_run_uuid", "22345678-1234-4234-8234-123456789abc", "visual-manifest.states[6].captures[1].capture_run_uuid"),
            (capture, "state_id", STATE_IDS[0], "visual-manifest.states[6].captures[1].state_id"),
            (capture, "captured_at_utc", "2026-07-21T23:59:59Z", "visual-manifest.states[6].captures[1].captured_at_utc"),
        )
        for target, key, value, field in rows:
            original = target[key]
            with self.subTest(field=field):
                target[key] = value
                self.fixture.write_visual()
                self.assertVisualField(field)
                target[key] = original
        self.fixture.manifest["run_started_at_utc"] = "2026-07-22T00:00:01Z"
        self.fixture.write_visual()
        self.assertVisualField("visual-manifest.run_started_at_utc")

    def test_capture_timestamps_must_be_nondecreasing_and_inside_window(self) -> None:
        first = self.fixture.manifest["states"][0]["captures"][0]
        second = self.fixture.manifest["states"][1]["captures"][0]
        second["captured_at_utc"] = first["captured_at_utc"]
        first["captured_at_utc"] = "2026-07-22T00:00:03Z"
        self.fixture.write_visual()
        self.assertVisualField("visual-manifest.states[2].captures[1].captured_at_utc")

    def test_canonical_artifact_closure_bytes_order_and_stable_digest_pass(self) -> None:
        result = self.fixture.artifact_result()
        expected = sha256((self.fixture.task_root / "artifact-manifest.json").read_bytes())
        self.assertEqual((), result.issues)
        self.assertEqual(expected, result.artifact_record_digest)
        self.assertRegex(result.artifact_record_digest, r"^[0-9a-f]{64}$")

    def test_artifact_omission_extra_order_hash_self_entry_and_self_digest_fail(self) -> None:
        transforms = (
            ("omission", lambda record: record["files"].pop(), "artifact-manifest.files"),
            ("extra", lambda record: record["files"].append({"path": f"{TASK_RELATIVE}/extra.json", "sha256": "0" * 64}), "artifact-manifest.files"),
            ("order", lambda record: record["files"].reverse(), "artifact-manifest.files"),
            ("hash", lambda record: record["files"][0].update(sha256="0" * 64), "artifact-manifest.files[1].sha256"),
            ("self-entry", lambda record: record["files"].append({"path": f"{TASK_RELATIVE}/artifact-manifest.json", "sha256": "0" * 64}), "artifact-manifest.files"),
            ("self-digest", lambda record: record.update(artifact_record_digest="0" * 64), "artifact-manifest"),
        )
        for name, transform, field in transforms:
            with self.subTest(name=name):
                self.fixture.write_artifact(transform)
                result = self.fixture.artifact_result()
                self.assertIn(field, {issue.field for issue in result.issues})

    def test_artifact_noncanonical_bom_and_newline_fail(self) -> None:
        path = self.fixture.task_root / "artifact-manifest.json"
        canonical = path.read_bytes()
        rows = (
            ("pretty", self.fixture.write_artifact(canonical=False)),
            ("bom", b"\xef\xbb\xbf" + canonical),
            ("no-newline", canonical.rstrip(b"\n")),
            ("extra-newline", canonical + b"\n"),
        )
        for name, data in rows:
            with self.subTest(name=name):
                path.write_bytes(data)
                result = self.fixture.artifact_result()
                self.assertIn("artifact-manifest.canonical_bytes", {issue.field for issue in result.issues})

    def test_apk_reference_source_path_and_hash_are_strict(self) -> None:
        path = self.fixture.task_root / "apk-reference.json"
        valid = json.loads(path.read_text(encoding="utf-8"))
        rows = (
            ("source", {**valid, "publication_source_sha": "f" * 40}, "apk-reference.publication_source_sha"),
            ("path", {**valid, "apk_path": "../outside.apk"}, "apk-reference.apk_path"),
            ("hash", {**valid, "apk_sha256": "0" * 64}, "apk-reference.apk_sha256"),
            ("extra", {**valid, "extra": True}, "apk-reference.extra"),
        )
        for name, record, field in rows:
            with self.subTest(name=name):
                path.write_text(json.dumps(record), encoding="utf-8")
                self.fixture.write_artifact()
                result = self.fixture.artifact_result()
                self.assertIn(field, {issue.field for issue in result.issues})

    def test_apk_reference_cannot_be_self_consistent_but_drift_from_visual_apk(self) -> None:
        apk = self.root / "android/app/build/outputs/apk/debug/app-debug.apk"
        apk.write_bytes(b"different but self-consistent APK")
        reference_path = self.fixture.task_root / "apk-reference.json"
        reference = json.loads(reference_path.read_text(encoding="utf-8"))
        reference["apk_sha256"] = sha256(apk.read_bytes())
        reference_path.write_text(json.dumps(reference), encoding="utf-8")
        self.fixture.write_artifact()

        result = self.fixture.artifact_result()

        self.assertIn("apk-reference.apk_sha256", {issue.field for issue in result.issues})

    def test_changing_included_bytes_changes_regenerated_record_digest(self) -> None:
        original = self.fixture.artifact_result().artifact_record_digest
        path = self.fixture.task_root / "manual.json"
        path.write_bytes(path.read_bytes() + b" ")
        self.fixture.write_artifact()
        changed = self.fixture.artifact_result().artifact_record_digest
        self.assertNotEqual(original, changed)


if __name__ == "__main__":
    unittest.main()
