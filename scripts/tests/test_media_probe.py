from __future__ import annotations

import sys
import unittest
from pathlib import Path
from typing import final

from scripts.submission_validation import MediaFacts, MediaProbeError
from scripts.verify_submission import CurlProbe


@final
class MediaProbeTest(unittest.TestCase):
    def _probe(self, script: str, timeout_seconds: float = 1.0) -> MediaFacts | MediaProbeError:
        probe = CurlProbe((sys.executable, "-c", script), timeout_seconds)
        return probe.inspect_media(Path("fixture.mp4"))

    def test_typed_facts_are_parsed_from_real_subprocess_stdout(self) -> None:
        result = self._probe(
            'print(\'{"streams":[{"codec_type":"video"},{"codec_type":"audio"}],"format":{"duration":"17.25"}}\')',
        )

        self.assertEqual(MediaFacts(17.25, 1), result)

    def test_missing_timeout_nonzero_and_invalid_json_fail_closed(self) -> None:
        failures = (
            CurlProbe(("leftovers-missing-ffprobe-binary",), 1.0).inspect_media(Path("fixture.mp4")),
            self._probe("import time; time.sleep(1)", 0.01),
            self._probe("raise SystemExit(7)"),
            self._probe("print('not json')"),
        )

        for result in failures:
            with self.subTest(result=result):
                self.assertIsInstance(result, MediaProbeError)


if __name__ == "__main__":
    _ = unittest.main()
