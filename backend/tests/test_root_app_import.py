from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys


def test_repo_root_import_needs_no_path_override() -> None:
    repository_root = Path(__file__).resolve().parents[2]
    environment = os.environ.copy()
    environment.pop("PYTHONPATH", None)

    result = subprocess.run(
        [
            sys.executable,
            "-c",
            "from app.main import create_app; "
            "assert create_app().title == 'LeftOVERS API'",
        ],
        cwd=repository_root,
        env=environment,
        capture_output=True,
        text=True,
        check=False,
    )

    assert result.returncode == 0, result.stderr
