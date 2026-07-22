from __future__ import annotations

import hashlib
import ipaddress
import json
import re
import struct
import subprocess
import zlib
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Annotated, ClassVar, Final, Literal, NewType, Protocol, Sequence
from urllib.parse import urlparse
from xml.etree import ElementTree

from pydantic import BaseModel, BeforeValidator, ConfigDict, Field, ValidationError


PublicUrl = NewType("PublicUrl", str)
CURRENT_SESSION_ID: Final = "019f706b-b713-7780-a456-f63ab18b173a"
REQUIRED_MARKERS: Final = (
    "disclosure:pantry", "disclosure:equipment", "disclosure:preferences", "disclosure:cooking-photo",
    "disclosure:store-false", "quota:installation-fairness", "quota:global-cost-boundary",
    "narrative:codex", "narrative:gpt-5.6", "instructions:build", "instructions:test", "license:mit",
)
SHA40: Final = r"^[0-9a-f]{40}$"
SHA256: Final = r"^[0-9a-f]{64}$"
CAPTURE_RUN_UUID: Final = r"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
EXPANSION_TASK_RELATIVE: Final = ".omo/evidence/leftovers-expansion/task-12"
MAX_EVIDENCE_FILE_BYTES: Final = 64 * 1024 * 1024


def _exact_integer(value: object) -> object:
    if type(value) is not int:
        raise ValueError("must be an exact JSON integer")
    return value


SchemaVersion2 = Annotated[Literal[2], BeforeValidator(_exact_integer)]
ExactZero = Annotated[Literal[0], BeforeValidator(_exact_integer)]
EXPANSION_STATE_IDS: Final = (
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
EXPANSION_CAPTURE_IDS: Final = tuple(
    ("04a-onion-quarter", "04b-onion-half") if step == 4 else (state_id,)
    for step, state_id in enumerate(EXPANSION_STATE_IDS, 1)
)
EXPANSION_UI_MARKERS: Final = {
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


class SubmissionRecord(BaseModel):
    model_config: ClassVar[ConfigDict] = ConfigDict(extra="forbid", frozen=True, strict=True)

    schema_version: SchemaVersion2
    publication_source_sha: str = Field(pattern=SHA40)
    status: str
    devpost_url: str
    submitted_at_utc: str
    repository_url: str
    release_url: str
    apk_url: str
    checksum_url: str
    backend_health_url: str
    youtube_url: str
    session_id: str
    apk_path: str
    checksum_path: str
    release_apk_path: str
    release_debug_hook_proof_path: str
    demo_media_path: str
    demo_media_evidence_path: str
    readme_path: str
    submission_path: str
    demo_script_path: str
    license_path: str


@dataclass(frozen=True, slots=True)
class Issue:
    field: str
    message: str


@dataclass(frozen=True, slots=True)
class ProbeResponse:
    status_code: int
    body: str


@dataclass(frozen=True, slots=True)
class MediaFacts:
    duration_seconds: float
    audio_stream_count: int


@dataclass(frozen=True, slots=True)
class MediaProbeError:
    detail: str


class SubmissionProbe(Protocol):
    def fetch(self, url: PublicUrl) -> ProbeResponse: ...

    def inspect_media(self, path: Path) -> MediaFacts | MediaProbeError: ...


class ReleaseProof(BaseModel):
    model_config: ClassVar[ConfigDict] = ConfigDict(extra="forbid", frozen=True, strict=True)

    schema_version: SchemaVersion2
    publication_source_sha: str = Field(pattern=SHA40)
    release_apk_sha256: str = Field(pattern=SHA256)
    manifest_debug_hook_matches: ExactZero
    dex_debug_hook_matches: ExactZero
    resource_debug_hook_matches: ExactZero
    archive_debug_hook_matches: ExactZero


class MediaEvidence(BaseModel):
    model_config: ClassVar[ConfigDict] = ConfigDict(extra="forbid", frozen=True, strict=True)

    schema_version: SchemaVersion2
    publication_source_sha: str = Field(pattern=SHA40)
    media_sha256: str = Field(pattern=SHA256)
    duration_seconds: float = Field(gt=0, lt=180)
    audio_stream_count: int = Field(ge=1)
    probe_tool: Literal["ffprobe"]


class VisualCapture(BaseModel):
    model_config: ClassVar[ConfigDict] = ConfigDict(extra="forbid", frozen=True, strict=True)

    capture_id: str
    state_id: str
    locale: Literal["en", "ko"]
    publication_source_sha: str = Field(pattern=SHA40)
    apk_sha256: str = Field(pattern=SHA256)
    device_serial: str = Field(min_length=1)
    device_model: str = Field(min_length=1)
    capture_run_uuid: str = Field(pattern=CAPTURE_RUN_UUID)
    captured_at_utc: str
    png_path: str = Field(min_length=1)
    png_sha256: str = Field(pattern=SHA256)
    png_width: int = Field(gt=0)
    png_height: int = Field(gt=0)
    xml_path: str = Field(min_length=1)
    xml_sha256: str = Field(pattern=SHA256)


class VisualState(BaseModel):
    model_config: ClassVar[ConfigDict] = ConfigDict(extra="forbid", frozen=True, strict=True)

    step_number: int
    state_id: str
    locale: Literal["en", "ko"]
    publication_source_sha: str = Field(pattern=SHA40)
    apk_sha256: str = Field(pattern=SHA256)
    device_serial: str = Field(min_length=1)
    device_model: str = Field(min_length=1)
    capture_run_uuid: str = Field(pattern=CAPTURE_RUN_UUID)
    captures: tuple[VisualCapture, ...]


class VisualManifest(BaseModel):
    model_config: ClassVar[ConfigDict] = ConfigDict(extra="forbid", frozen=True, strict=True)

    schema_version: SchemaVersion2
    publication_source_sha: str = Field(pattern=SHA40)
    apk_sha256: str = Field(pattern=SHA256)
    device_serial: str = Field(min_length=1)
    device_model: str = Field(min_length=1)
    capture_run_uuid: str = Field(pattern=CAPTURE_RUN_UUID)
    run_started_at_utc: str
    run_finished_at_utc: str
    states: tuple[VisualState, ...]


class ArtifactFile(BaseModel):
    model_config: ClassVar[ConfigDict] = ConfigDict(extra="forbid", frozen=True, strict=True)

    path: str = Field(min_length=1)
    sha256: str = Field(pattern=SHA256)


class ArtifactManifest(BaseModel):
    model_config: ClassVar[ConfigDict] = ConfigDict(extra="forbid", frozen=True, strict=True)

    schema_version: SchemaVersion2
    publication_source_sha: str = Field(pattern=SHA40)
    files: tuple[ArtifactFile, ...]


class ApkReference(BaseModel):
    model_config: ClassVar[ConfigDict] = ConfigDict(extra="forbid", frozen=True, strict=True)

    schema_version: SchemaVersion2
    publication_source_sha: str = Field(pattern=SHA40)
    apk_path: str = Field(min_length=1)
    apk_sha256: str = Field(pattern=SHA256)


@dataclass(frozen=True, slots=True)
class ArtifactValidationResult:
    issues: tuple[Issue, ...]
    artifact_record_digest: str | None


def public_url(value: str) -> PublicUrl | None:
    parsed = urlparse(value)
    hostname = parsed.hostname
    if parsed.scheme != "https" or not hostname or not parsed.path:
        return None
    lowered = value.lower()
    if any(token in lowered for token in ("placeholder", "example.", "localhost", "changeme", "todo")):
        return None
    try:
        address = ipaddress.ip_address(hostname)
    except ValueError:
        if hostname.endswith((".local", ".test", ".invalid")):
            return None
    else:
        if not address.is_global:
            return None
    return PublicUrl(value)


def _path(root: Path, relative: str) -> Path | None:
    if not relative:
        return None
    candidate = (root / relative).resolve()
    return candidate if candidate.is_relative_to(root.resolve()) else None


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _validation_issues(prefix: str, error: ValidationError) -> tuple[Issue, ...]:
    issues: list[Issue] = []
    for detail in error.errors(include_url=False, include_context=False, include_input=False):
        field = prefix
        for part in detail["loc"]:
            field += f"[{part + 1}]" if isinstance(part, int) else f".{part}"
        issues.append(Issue(field, detail["msg"]))
    return tuple(issues)


def _git(root: Path, *args: str) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(("git", *args), cwd=root, check=False, capture_output=True)


def _source_path_allowed(path: str) -> bool:
    if path.startswith(".omo/evidence/leftovers-expansion/"):
        return True
    if path.startswith(".omo/evidence/leftovers/task-12-publication/"):
        return True
    return path in {
        ".omo/start-work/ledger.jsonl",
        ".omo/boulder.json",
        ".omo/plans/leftovers.md",
        "dist/app-debug.apk.sha256",
        "dist/release-debug-hooks.json",
        "docs/submission.md",
        "docs/demo-script.md",
        "docs/asset-provenance.md",
    }


def validate_publication_source(
    records: Sequence[SubmissionRecord | ReleaseProof | MediaEvidence],
    root: Path,
) -> tuple[Issue, ...]:
    issues: list[Issue] = []
    if not records:
        return (Issue("publication_source_sha", "at least one strict v2 publication record is required"),)
    source_shas = {record.publication_source_sha for record in records}
    if len(source_shas) != 1:
        return (Issue("publication_source_sha", "all actual publication records must use the identical source SHA"),)
    source_sha = next(iter(source_shas))
    if not (root / ".git").exists():
        return (Issue("git_repository", "publication source validation requires a Git worktree"),)
    status = _git(root, "status", "--porcelain=v1", "-z")
    if status.returncode != 0 or status.stdout:
        issues.append(Issue("git_worktree", "must be clean while validating publication evidence"))
    exists = _git(root, "cat-file", "-e", f"{source_sha}^{{commit}}")
    if exists.returncode != 0:
        issues.append(Issue("publication_source_sha", "commit does not exist in this repository"))
        return tuple(issues)
    ancestor = _git(root, "merge-base", "--is-ancestor", source_sha, "HEAD")
    if ancestor.returncode != 0:
        issues.append(Issue("publication_source_sha", "commit must be an ancestor of current HEAD"))
        return tuple(issues)
    changed = _git(root, "diff", "--no-renames", "--name-only", "-z", source_sha, "HEAD", "--")
    if changed.returncode != 0:
        issues.append(Issue("publication_source_sha", "could not compare source bytes with current HEAD"))
        return tuple(issues)
    try:
        paths = tuple(part.decode("utf-8") for part in changed.stdout.split(b"\0") if part)
    except UnicodeDecodeError:
        issues.append(Issue("publication_source_sha", "changed path is not canonical UTF-8"))
        return tuple(issues)
    for path in paths:
        if not _source_path_allowed(path):
            issues.append(Issue(f"publication_source_sha.{path}", "tracked bytes differ outside the exact post-source allowlist"))
    return tuple(issues)


def _safe_path(
    root: Path,
    relative: str,
    field: str,
    evidence_root: Path | None = None,
) -> tuple[Path | None, tuple[Issue, ...]]:
    if (
        not relative
        or "\\" in relative
        or relative.startswith(("/", "//"))
        or re.match(r"^[A-Za-z]:", relative)
        or any(":" in part for part in relative.split("/"))
    ):
        return None, (Issue(field, "path must be nonempty, repo-relative, and forward-slash only"),)
    parts = relative.split("/")
    if any(part in {"", ".", ".."} for part in parts):
        return None, (Issue(field, "path cannot contain empty, dot, or parent segments"),)
    pure = PurePosixPath(relative)
    if pure.is_absolute():
        return None, (Issue(field, "absolute paths are forbidden"),)
    root_resolved = root.resolve()
    candidate = root.joinpath(*parts)
    current = root
    for part in parts:
        current /= part
        if current.is_symlink() or current.is_junction():
            return None, (Issue(field, "symlinks and junctions are forbidden in evidence paths"),)
    resolved = candidate.resolve(strict=False)
    if not resolved.is_relative_to(root_resolved):
        return None, (Issue(field, "resolved path escapes the repository"),)
    if evidence_root is not None and not resolved.is_relative_to(evidence_root.resolve()):
        return None, (Issue(field, "resolved path escapes the evidence root"),)
    return candidate, ()


def _utc(value: str) -> datetime | None:
    if re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z", value) is None:
        return None
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError:
        return None
    return parsed if parsed.tzinfo == timezone.utc else None


def _png_dimensions(data: bytes) -> tuple[int, int] | None:
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        return None
    offset = 8
    dimensions: tuple[int, int] | None = None
    idat = bytearray()
    channels = bit_depth = interlace = 0
    saw_iend = False
    chunk_index = 0
    while offset < len(data):
        if offset + 12 > len(data):
            return None
        length = struct.unpack(">I", data[offset : offset + 4])[0]
        kind = data[offset + 4 : offset + 8]
        end = offset + 12 + length
        if end > len(data):
            return None
        body = data[offset + 8 : offset + 8 + length]
        expected_crc = struct.unpack(">I", data[offset + 8 + length : end])[0]
        if zlib.crc32(kind + body) & 0xFFFFFFFF != expected_crc:
            return None
        if chunk_index == 0:
            if kind != b"IHDR" or length != 13:
                return None
            width, height, bit_depth, color_type, compression, filter_method, interlace = struct.unpack(
                ">IIBBBBB", body
            )
            allowed_depths = {
                0: {1, 2, 4, 8, 16},
                2: {8, 16},
                3: {1, 2, 4, 8},
                4: {8, 16},
                6: {8, 16},
            }
            if (
                width <= 0
                or height <= 0
                or bit_depth not in allowed_depths.get(color_type, set())
                or compression != 0
                or filter_method != 0
                or interlace != 0
            ):
                return None
            channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color_type]
            dimensions = (width, height)
        elif kind == b"IDAT":
            idat.extend(body)
        elif kind == b"IEND":
            if body or end != len(data):
                return None
            saw_iend = True
            break
        offset = end
        chunk_index += 1
    if dimensions is None or not idat or not saw_iend:
        return None
    inflater = zlib.decompressobj()
    try:
        pixels = inflater.decompress(bytes(idat), MAX_EVIDENCE_FILE_BYTES + 1)
        pixels += inflater.flush(MAX_EVIDENCE_FILE_BYTES + 1 - len(pixels))
    except (ValueError, zlib.error):
        return None
    if len(pixels) > MAX_EVIDENCE_FILE_BYTES or not inflater.eof or inflater.unused_data:
        return None
    if interlace == 0:
        width, height = dimensions
        row_bytes = (width * channels * bit_depth + 7) // 8
        if len(pixels) != height * (row_bytes + 1):
            return None
        if any(pixels[row * (row_bytes + 1)] > 4 for row in range(height)):
            return None
    return dimensions


def _xml_text(data: bytes) -> str | None:
    try:
        decoded = data.decode("utf-8")
    except UnicodeDecodeError:
        return None
    upper = decoded.upper()
    if "<!DOCTYPE" in upper or "<!ENTITY" in upper:
        return None
    try:
        root = ElementTree.fromstring(data)
    except ElementTree.ParseError:
        return None
    if root.tag != "hierarchy" or not list(root.iter())[1:]:
        return None
    values: list[str] = []
    for element in root.iter():
        values.extend(element.attrib.values())
        if element.text:
            values.append(element.text)
    return "\n".join(values)


def _read_visual_manifest(path: Path) -> tuple[VisualManifest | None, tuple[Issue, ...]]:
    try:
        data = path.read_bytes()
    except OSError:
        return None, (Issue("visual-manifest", "visual-manifest.json is missing"),)
    try:
        return VisualManifest.model_validate_json(data), ()
    except ValidationError as error:
        return None, _validation_issues("visual-manifest", error)


def validate_expansion_visual_evidence(
    root: Path,
    evidence_root: Path,
    *,
    approved_source_sha: str,
    apk_sha256: str,
    device_serial: str,
    device_model: str,
    run_started_at_utc: str,
    run_finished_at_utc: str,
) -> tuple[Issue, ...]:
    manifest, parse_issues = _read_visual_manifest(evidence_root / "visual-manifest.json")
    if manifest is None:
        return parse_issues
    issues = list(parse_issues)
    expected_top = {
        "publication_source_sha": approved_source_sha,
        "apk_sha256": apk_sha256,
        "device_serial": device_serial,
        "device_model": device_model,
        "run_started_at_utc": run_started_at_utc,
        "run_finished_at_utc": run_finished_at_utc,
    }
    for field, expected in expected_top.items():
        if getattr(manifest, field) != expected:
            issues.append(Issue(f"visual-manifest.{field}", f"must equal expected {field}"))
    start = _utc(run_started_at_utc)
    finish = _utc(run_finished_at_utc)
    if start is None or finish is None or start > finish:
        issues.append(Issue("visual-manifest.run_window", "expected capture window must be ordered RFC3339 UTC"))
        return tuple(issues)
    actual_states = tuple((state.step_number, state.state_id) for state in manifest.states)
    expected_states = tuple(enumerate(EXPANSION_STATE_IDS, 1))
    if actual_states != expected_states:
        issues.append(Issue("visual-manifest.states", "must contain exactly the 11 canonical states in order"))
    seen_paths: set[str] = set()
    seen_casefold_paths: set[str] = set()
    seen_hashes: set[str] = set()
    previous_timestamp: datetime | None = None
    for state_index, state in enumerate(manifest.states, 1):
        state_field = f"visual-manifest.states[{state_index}]"
        expected_state = EXPANSION_STATE_IDS[state_index - 1] if state_index <= len(EXPANSION_STATE_IDS) else None
        expected_locale = "en" if state_index <= 5 or state_index == 11 else "ko"
        for field, expected in {
            "step_number": state_index,
            "state_id": expected_state,
            "locale": expected_locale,
            "publication_source_sha": approved_source_sha,
            "apk_sha256": apk_sha256,
            "device_serial": device_serial,
            "device_model": device_model,
            "capture_run_uuid": manifest.capture_run_uuid,
        }.items():
            if getattr(state, field) != expected:
                issues.append(Issue(f"{state_field}.{field}", f"must match canonical state {field}"))
        expected_captures = EXPANSION_CAPTURE_IDS[state_index - 1] if state_index <= len(EXPANSION_CAPTURE_IDS) else ()
        if tuple(capture.capture_id for capture in state.captures) != expected_captures:
            issues.append(Issue(f"{state_field}.captures", "must contain exactly the canonical ordered capture IDs"))
        for capture_index, capture in enumerate(state.captures, 1):
            capture_field = f"{state_field}.captures[{capture_index}]"
            for field, expected in {
                "state_id": state.state_id,
                "locale": state.locale,
                "publication_source_sha": approved_source_sha,
                "apk_sha256": apk_sha256,
                "device_serial": device_serial,
                "device_model": device_model,
                "capture_run_uuid": manifest.capture_run_uuid,
            }.items():
                if getattr(capture, field) != expected:
                    issues.append(Issue(f"{capture_field}.{field}", f"must match the containing state and expected {field}"))
            timestamp = _utc(capture.captured_at_utc)
            if timestamp is None or not start <= timestamp <= finish or (previous_timestamp is not None and timestamp < previous_timestamp):
                issues.append(Issue(f"{capture_field}.captured_at_utc", "must be nondecreasing RFC3339 UTC inside the run window"))
            if timestamp is not None:
                previous_timestamp = timestamp
            assets = (
                ("png", capture.png_path, capture.png_sha256),
                ("xml", capture.xml_path, capture.xml_sha256),
            )
            resolved: dict[str, Path] = {}
            for kind, relative, digest in assets:
                field = f"{capture_field}.{kind}_path"
                path, path_issues = _safe_path(root, relative, field, evidence_root)
                issues.extend(path_issues)
                folded = relative.casefold()
                if relative in seen_paths or folded in seen_casefold_paths:
                    issues.append(Issue(field, "asset paths must be unique, including case-folded form"))
                seen_paths.add(relative)
                seen_casefold_paths.add(folded)
                hash_field = f"{capture_field}.{kind}_sha256"
                if digest in seen_hashes:
                    issues.append(Issue(hash_field, "asset hashes must be unique"))
                seen_hashes.add(digest)
                if path is None:
                    continue
                if not path.is_file():
                    issues.append(Issue(field, "asset file is absent"))
                    continue
                if path.stat().st_size > MAX_EVIDENCE_FILE_BYTES:
                    issues.append(Issue(field, "asset file exceeds the verifier size boundary"))
                    continue
                resolved[kind] = path
                if _sha256(path) != digest:
                    issues.append(Issue(hash_field, "declared hash does not match file bytes"))
            png_path = resolved.get("png")
            if png_path is not None and _sha256(png_path) == capture.png_sha256:
                dimensions = _png_dimensions(png_path.read_bytes())
                if dimensions is None:
                    issues.append(Issue(f"{capture_field}.png_path", "file is not a valid PNG with a valid IHDR"))
                else:
                    if dimensions[0] != capture.png_width:
                        issues.append(Issue(f"{capture_field}.png_width", "declared width does not match decoded PNG width"))
                    if dimensions[1] != capture.png_height:
                        issues.append(Issue(f"{capture_field}.png_height", "declared height does not match decoded PNG height"))
            xml_path = resolved.get("xml")
            if xml_path is not None and _sha256(xml_path) == capture.xml_sha256:
                xml_text = _xml_text(xml_path.read_bytes())
                markers = EXPANSION_UI_MARKERS.get(capture.capture_id, ())
                if xml_text is None or any(marker not in xml_text for marker in markers):
                    issues.append(Issue(f"{capture_field}.xml_path", "XML must be a nonempty hierarchy bound to the canonical state markers"))
    return tuple(issues)


def _canonical_json_bytes(record: ArtifactManifest) -> bytes:
    return (
        json.dumps(
            record.model_dump(mode="json"),
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")


def validate_expansion_artifact_manifest(
    root: Path,
    evidence_root: Path,
    approved_source_sha: str,
    apk_sha256: str,
) -> ArtifactValidationResult:
    issues: list[Issue] = []
    visual, visual_issues = _read_visual_manifest(evidence_root / "visual-manifest.json")
    issues.extend(visual_issues)
    if visual is not None and visual.apk_sha256 != apk_sha256:
        issues.append(Issue("visual-manifest.apk_sha256", "must equal the expected APK SHA"))
    path = evidence_root / "artifact-manifest.json"
    try:
        raw = path.read_bytes()
    except OSError:
        return ArtifactValidationResult(tuple(issues) + (Issue("artifact-manifest", "artifact-manifest.json is missing"),), None)
    if raw.startswith(b"\xef\xbb\xbf"):
        return ArtifactValidationResult(
            tuple(issues) + (Issue("artifact-manifest.canonical_bytes", "UTF-8 BOM is forbidden"),),
            None,
        )
    try:
        record = ArtifactManifest.model_validate_json(raw)
    except ValidationError as error:
        return ArtifactValidationResult(tuple(issues) + (Issue("artifact-manifest", str(error)),), None)
    if record.publication_source_sha != approved_source_sha:
        issues.append(Issue("artifact-manifest.publication_source_sha", "must equal the approved source SHA"))
    try:
        task_relative = evidence_root.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        issues.append(Issue("artifact-manifest", "task root must be inside the repository"))
        return ArtifactValidationResult(tuple(issues), None)
    if task_relative != EXPANSION_TASK_RELATIVE:
        issues.append(Issue("artifact-manifest", f"task root must be exactly {EXPANSION_TASK_RELATIVE}"))
    expected_paths = {
        f"{task_relative}/{name}"
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
    if visual is not None:
        for state in visual.states:
            for capture in state.captures:
                expected_paths.update((capture.png_path, capture.xml_path))
    actual_paths = tuple(entry.path for entry in record.files)
    expected_order = tuple(sorted(expected_paths))
    if actual_paths != expected_order:
        issues.append(Issue("artifact-manifest.files", "must be the exact included-file closure in Unicode code-point order"))
    for index, entry in enumerate(record.files, 1):
        field = f"artifact-manifest.files[{index}]"
        entry_path, entry_issues = _safe_path(root, entry.path, f"{field}.path", evidence_root)
        issues.extend(entry_issues)
        if PurePosixPath(entry.path).parent != PurePosixPath(task_relative):
            issues.append(Issue(f"{field}.path", "every evidence basename must resolve directly under the task root"))
        if entry_path is None or not entry_path.is_file():
            issues.append(Issue(f"{field}.path", "included file is absent"))
        elif _sha256(entry_path) != entry.sha256:
            issues.append(Issue(f"{field}.sha256", "included-file hash does not match bytes on disk"))
    if raw != _canonical_json_bytes(record):
        issues.append(Issue("artifact-manifest.canonical_bytes", "must be canonical compact sorted-key UTF-8 without BOM plus exactly one LF"))
    submission_path = evidence_root / "submission-draft.json"
    try:
        submission = SubmissionRecord.model_validate_json(submission_path.read_bytes())
    except (OSError, ValidationError) as error:
        issues.append(Issue("submission-draft", f"must be a strict v2 SubmissionRecord: {error}"))
    else:
        if submission.publication_source_sha != approved_source_sha:
            issues.append(Issue("submission-draft.publication_source_sha", "must equal the approved source SHA"))
    reference_path = evidence_root / "apk-reference.json"
    try:
        reference = ApkReference.model_validate_json(reference_path.read_bytes())
    except OSError as error:
        issues.append(Issue("apk-reference", str(error)))
    except ValidationError as error:
        issues.extend(_validation_issues("apk-reference", error))
    else:
        if reference.publication_source_sha != approved_source_sha:
            issues.append(Issue("apk-reference.publication_source_sha", "must equal the approved source SHA"))
        if reference.apk_sha256 != apk_sha256 or (visual is not None and reference.apk_sha256 != visual.apk_sha256):
            issues.append(Issue("apk-reference.apk_sha256", "must equal the expected and visual-manifest APK SHA"))
        apk_path, apk_path_issues = _safe_path(root, reference.apk_path, "apk-reference.apk_path")
        issues.extend(apk_path_issues)
        if apk_path is None or not apk_path.is_file():
            issues.append(Issue("apk-reference.apk_path", "referenced APK is absent"))
        elif _sha256(apk_path) != reference.apk_sha256:
            issues.append(Issue("apk-reference.apk_sha256", "referenced APK hash does not match bytes on disk"))
    digest = hashlib.sha256(raw).hexdigest() if not issues else None
    return ArtifactValidationResult(tuple(issues), digest)


def _files(record: SubmissionRecord, root: Path, probe: SubmissionProbe) -> tuple[Issue, ...]:
    issues: list[Issue] = []
    publication_records: list[SubmissionRecord | ReleaseProof | MediaEvidence] = [record]
    paths = {
        "apk_path": record.apk_path,
        "checksum_path": record.checksum_path,
        "release_apk_path": record.release_apk_path,
        "release_debug_hook_proof_path": record.release_debug_hook_proof_path,
        "demo_media_path": record.demo_media_path,
        "demo_media_evidence_path": record.demo_media_evidence_path,
        "readme_path": record.readme_path,
        "submission_path": record.submission_path,
        "demo_script_path": record.demo_script_path,
        "license_path": record.license_path,
    }
    resolved: dict[str, Path] = {}
    for field, relative in paths.items():
        path = _path(root, relative)
        if path is None or not path.is_file():
            issues.append(Issue(field, "file is missing or outside the repository"))
        else:
            resolved[field] = path
    if {"apk_path", "checksum_path"} <= resolved.keys():
        digest = _sha256(resolved["apk_path"])
        if digest not in resolved["checksum_path"].read_text(encoding="utf-8").lower():
            issues.append(Issue("checksum_path", "checksum does not match the debug APK"))
    proof = resolved.get("release_debug_hook_proof_path")
    if proof is not None:
        text = proof.read_text(encoding="utf-8")
        try:
            parsed_proof = ReleaseProof.model_validate_json(text)
        except ValidationError:
            issues.append(Issue("release_debug_hook_proof_path", "must be strict schema-v2 release-proof JSON with every hook count zero"))
        else:
            publication_records.append(parsed_proof)
            release = resolved.get("release_apk_path")
            if release is not None and parsed_proof.release_apk_sha256 != _sha256(release):
                issues.append(Issue("release_apk_path", "release APK does not match the debug-hook proof checksum"))
    evidence = resolved.get("demo_media_evidence_path")
    if evidence is not None:
        try:
            parsed_evidence = MediaEvidence.model_validate_json(evidence.read_text(encoding="utf-8"))
        except ValidationError:
            issues.append(Issue("demo_media_evidence_path", "must be strict schema-v2 ffprobe evidence with duration below 180 seconds and audio"))
        else:
            publication_records.append(parsed_evidence)
            media = resolved.get("demo_media_path")
            if media is not None:
                observed = probe.inspect_media(media)
                evidence_mismatch = parsed_evidence.media_sha256 != _sha256(media)
                if isinstance(observed, MediaProbeError):
                    issues.append(Issue("demo_media_evidence_path", f"ffprobe failed: {observed.detail}"))
                elif (
                    evidence_mismatch
                    or not 0 < observed.duration_seconds < 180
                    or observed.audio_stream_count < 1
                    or observed.duration_seconds != parsed_evidence.duration_seconds
                    or observed.audio_stream_count != parsed_evidence.audio_stream_count
                ):
                    issues.append(Issue("demo_media_evidence_path", "demo evidence does not match bounded ffprobe observations and media checksum"))
    readme = resolved.get("readme_path")
    if readme is not None:
        text = readme.read_text(encoding="utf-8")
        for marker in REQUIRED_MARKERS:
            if f"<!-- {marker} -->" not in text:
                issues.append(Issue(f"README.{marker}", "required machine-readable section marker is missing"))
    for field in ("readme_path", "submission_path", "demo_script_path"):
        path = resolved.get(field)
        if path is not None and re.search(r"[\uac00-\ud7a3]", path.read_text(encoding="utf-8")):
            issues.append(Issue(field, "document must be English"))
    license_path = resolved.get("license_path")
    if license_path is not None and "MIT License" not in license_path.read_text(encoding="utf-8"):
        issues.append(Issue("license_path", "MIT license text is missing"))
    issues.extend(validate_publication_source(tuple(publication_records), root))
    return tuple(issues)


def validate_submission(record: SubmissionRecord, root: Path, probe: SubmissionProbe) -> tuple[Issue, ...]:
    issues: list[Issue] = []
    if record.status != "Submitted":
        issues.append(Issue("status", "must be Submitted"))
    try:
        _ = datetime.strptime(record.submitted_at_utc, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError:
        issues.append(Issue("submitted_at_utc", "must be a UTC submission timestamp"))
    if record.session_id != CURRENT_SESSION_ID:
        issues.append(Issue("session_id", f"must equal {CURRENT_SESSION_ID}"))
    valid_urls: dict[str, PublicUrl] = {}
    url_values = (
        ("repository_url", record.repository_url), ("release_url", record.release_url),
        ("apk_url", record.apk_url), ("checksum_url", record.checksum_url),
        ("backend_health_url", record.backend_health_url), ("youtube_url", record.youtube_url),
        ("devpost_url", record.devpost_url),
    )
    for field, value in url_values:
        url = public_url(value)
        if url is None:
            issues.append(Issue(field, "must be a non-placeholder public HTTPS URL"))
        else:
            valid_urls[field] = url
    patterns = {
        "repository_url": r"^https://github\.com/[^/]+/[^/]+/?$",
        "release_url": r"^https://github\.com/[^/]+/[^/]+/releases/tag/v0\.1\.0-demo/?$",
        "apk_url": r"^https://github\.com/[^/]+/[^/]+/releases/download/v0\.1\.0-demo/app-debug\.apk$",
        "checksum_url": r"^https://github\.com/[^/]+/[^/]+/releases/download/v0\.1\.0-demo/app-debug\.apk\.sha256$",
        "backend_health_url": r"^https://(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+run\.app/health$",
        "youtube_url": r"^https://(?:www\.)?(?:youtube\.com/watch\?v=|youtu\.be/)[A-Za-z0-9_-]+$",
        "devpost_url": r"^https://openai\.devpost\.com/software/[a-z0-9-]+/?$",
    }
    for field, pattern in patterns.items():
        url = valid_urls.get(field)
        if url is not None and re.fullmatch(pattern, str(url)) is None:
            issues.append(Issue(field, "URL does not match the required public artifact shape"))
            _ = valid_urls.pop(field)
    repository = valid_urls.get("repository_url")
    if repository is not None:
        base = str(repository).rstrip("/")
        expected_artifacts = {
            "release_url": f"{base}/releases/tag/v0.1.0-demo",
            "apk_url": f"{base}/releases/download/v0.1.0-demo/app-debug.apk",
            "checksum_url": f"{base}/releases/download/v0.1.0-demo/app-debug.apk.sha256",
        }
        for field, expected_url in expected_artifacts.items():
            if field in valid_urls and str(valid_urls[field]) != expected_url:
                issues.append(Issue(field, "must belong to repository_url owner and repository"))
                _ = valid_urls.pop(field)
    apk = _path(root, record.apk_path)
    local_apk_digest = _sha256(apk) if apk is not None and apk.is_file() else None
    for field, url in valid_urls.items():
        response = probe.fetch(url)
        accepted = response.status_code == 200 if field in {"backend_health_url", "devpost_url"} else response.status_code in {200, 206}
        if not accepted:
            issues.append(Issue(field, f"public URL returned HTTP {response.status_code}"))
        if field == "checksum_url" and accepted and local_apk_digest is not None:
            checksum = re.fullmatch(r"\s*([0-9a-fA-F]{64})\s+\*?app-debug\.apk\s*", response.body)
            if checksum is None or checksum.group(1).lower() != local_apk_digest:
                issues.append(Issue(field, "published checksum does not match the local debug APK"))
        if field == "devpost_url" and not {"LeftOVERS", "Apps for Your Life"} <= set(re.findall(r"LeftOVERS|Apps for Your Life", response.body)):
            issues.append(Issue(field, "public page is missing the title or challenge name"))
    issues.extend(_files(record, root, probe))
    return tuple(issues)
