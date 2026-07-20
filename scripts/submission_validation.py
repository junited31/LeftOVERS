from __future__ import annotations

import hashlib
import ipaddress
import re
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import ClassVar, Final, Literal, NewType, Protocol
from urllib.parse import urlparse

from pydantic import BaseModel, ConfigDict, Field, ValidationError


PublicUrl = NewType("PublicUrl", str)
CURRENT_SESSION_ID: Final = "019f706b-b713-7780-a456-f63ab18b173a"
REQUIRED_MARKERS: Final = (
    "disclosure:pantry", "disclosure:equipment", "disclosure:preferences", "disclosure:cooking-photo",
    "disclosure:store-false", "quota:installation-fairness", "quota:global-cost-boundary",
    "narrative:codex", "narrative:gpt-5.6", "instructions:build", "instructions:test", "license:mit",
)


@dataclass(frozen=True, slots=True)
class SubmissionRecord:
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

    schema_version: Literal[1]
    release_apk_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    manifest_debug_hook_matches: Literal[0]
    dex_debug_hook_matches: Literal[0]
    resource_debug_hook_matches: Literal[0]
    archive_debug_hook_matches: Literal[0]


class MediaEvidence(BaseModel):
    model_config: ClassVar[ConfigDict] = ConfigDict(extra="forbid", frozen=True, strict=True)

    schema_version: Literal[1]
    media_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    duration_seconds: float = Field(gt=0, lt=180)
    audio_stream_count: int = Field(ge=1)
    probe_tool: Literal["ffprobe"]


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


def _files(record: SubmissionRecord, root: Path, probe: SubmissionProbe) -> tuple[Issue, ...]:
    issues: list[Issue] = []
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
        text = proof.read_text(encoding="utf-8").lower()
        try:
            parsed_proof = ReleaseProof.model_validate_json(text)
        except ValidationError:
            issues.append(Issue("release_debug_hook_proof_path", "must be strict release-proof JSON with every hook count zero"))
        else:
            release = resolved.get("release_apk_path")
            if release is not None and parsed_proof.release_apk_sha256 != _sha256(release):
                issues.append(Issue("release_apk_path", "release APK does not match the debug-hook proof checksum"))
    evidence = resolved.get("demo_media_evidence_path")
    if evidence is not None:
        try:
            parsed_evidence = MediaEvidence.model_validate_json(evidence.read_text(encoding="utf-8"))
        except ValidationError:
            issues.append(Issue("demo_media_evidence_path", "must be strict ffprobe evidence with duration below 180 seconds and audio"))
        else:
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
