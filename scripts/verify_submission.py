# /// script
# requires-python = ">=3.14"
# dependencies = ["pydantic==2.13.4"]
# ///

# ─── How to run ───
# 1. Install uv (if not installed):
#      curl -LsSf https://astral.sh/uv/install.sh | sh
# 2. Run directly (no venv, no pip install needed):
#      python scripts/verify_submission.py [--record docs/submission.md]
# 3. Or run with uv:
#      uv run scripts/verify_submission.py [--record docs/submission.md]
# ─────────────────

from __future__ import annotations

import re
import subprocess
import sys
from dataclasses import dataclass
from math import isfinite
from pathlib import Path
from typing import ClassVar, Final, override

from pydantic import BaseModel, ConfigDict, TypeAdapter, ValidationError

if not __package__:
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from scripts.submission_validation import (
    Issue,
    MediaFacts,
    MediaProbeError,
    ProbeResponse,
    PublicUrl,
    SubmissionProbe,
    SubmissionRecord,
    validate_submission,
)


RECORD_START: Final = "<!-- submission-record:start -->"
RECORD_END: Final = "<!-- submission-record:end -->"


@dataclass(frozen=True, slots=True)
class RecordParseError(Exception):
    path: Path
    detail: str

    @override
    def __str__(self) -> str:
        return f"{self.path}: {self.detail}"


class FfprobeStream(BaseModel):
    model_config: ClassVar[ConfigDict] = ConfigDict(extra="ignore", frozen=True)

    codec_type: str


class FfprobeFormat(BaseModel):
    model_config: ClassVar[ConfigDict] = ConfigDict(extra="ignore", frozen=True)

    duration: str


class FfprobeOutput(BaseModel):
    model_config: ClassVar[ConfigDict] = ConfigDict(extra="ignore", frozen=True)

    streams: tuple[FfprobeStream, ...] = ()
    format: FfprobeFormat


class CurlProbe:
    def __init__(self, media_command: tuple[str, ...] = ("ffprobe",), media_timeout_seconds: float = 20.0) -> None:
        self.media_command: tuple[str, ...] = media_command
        self.media_timeout_seconds: float = media_timeout_seconds

    def fetch(self, url: PublicUrl) -> ProbeResponse:
        marker = "__LEFTOVERS_HTTP_STATUS__"
        try:
            result = subprocess.run(
                (
                    "curl", "--location", "--silent", "--show-error", "--max-time", "15",
                    "--max-filesize", "131072", "--range", "0-65535", "--write-out",
                    f"\n{marker}%{{http_code}}", str(url),
                ),
                check=False,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=20,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            return ProbeResponse(0, str(error))
        body, separator, status = result.stdout.rpartition(marker)
        if not separator or not status.strip().isdigit():
            return ProbeResponse(0, result.stderr.strip())
        return ProbeResponse(int(status.strip()), body)

    def inspect_media(self, path: Path) -> MediaFacts | MediaProbeError:
        try:
            result = subprocess.run(
                (
                    *self.media_command, "-v", "error", "-show_entries", "format=duration",
                    "-show_entries", "stream=codec_type", "-of", "json", str(path),
                ),
                check=False,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=self.media_timeout_seconds,
            )
        except OSError as error:
            return MediaProbeError(str(error))
        except subprocess.TimeoutExpired:
            return MediaProbeError("ffprobe timed out")
        if result.returncode != 0:
            return MediaProbeError(f"ffprobe exited {result.returncode}")
        try:
            output = FfprobeOutput.model_validate_json(result.stdout)
            duration = float(output.format.duration)
        except (ValidationError, ValueError) as error:
            return MediaProbeError(f"invalid ffprobe output: {error}")
        if not isfinite(duration):
            return MediaProbeError("ffprobe duration is not finite")
        audio_count = sum(stream.codec_type == "audio" for stream in output.streams)
        return MediaFacts(duration_seconds=duration, audio_stream_count=audio_count)


def parse_record(path: Path) -> SubmissionRecord:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        raise RecordParseError(path, str(error)) from error
    if path.suffix.lower() == ".md":
        start = text.find(RECORD_START)
        end = text.find(RECORD_END)
        if start < 0 or end <= start:
            raise RecordParseError(path, "submission record markers are missing")
        text = text[start + len(RECORD_START) : end].strip()
        text = re.sub(r"^```json\s*|\s*```$", "", text, flags=re.DOTALL)
    try:
        return TypeAdapter(SubmissionRecord).validate_json(text)
    except ValidationError as error:
        raise RecordParseError(path, str(error)) from error


def verify_submission(record: SubmissionRecord, root: Path, probe: SubmissionProbe) -> tuple[Issue, ...]:
    issues = list(validate_submission(record, root, probe))
    if (root / ".git").exists():
        worktree = subprocess.run(("git", "status", "--porcelain"), cwd=root, check=False, capture_output=True, text=True)
        if worktree.returncode != 0 or worktree.stdout.strip():
            issues.append(Issue("git_worktree", "must be clean at the publication commit"))
    return tuple(issues)


def main() -> int:
    args = sys.argv[1:]
    if args and (len(args) != 2 or args[0] != "--record"):
        print("Usage: python scripts/verify_submission.py [--record PATH]")
        return 2
    path = Path(args[1]) if args else Path("docs/submission.md")
    try:
        record = parse_record(path)
    except RecordParseError as error:
        print(f"[FAIL] record: {error}")
        return 1
    issues = verify_submission(record, Path.cwd(), CurlProbe())
    if issues:
        for issue in issues:
            print(f"[FAIL] {issue.field}: {issue.message}")
        print(f"Submission verification failed with {len(issues)} issue(s).")
        return 1
    print("Submission verification passed: public artifacts and disclosures are complete.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
