from __future__ import annotations

from collections import defaultdict
from datetime import UTC, datetime, timedelta, timezone

import anyio
import pytest


class AtomicCounterBackend:
    def __init__(self) -> None:
        self.counts: defaultdict[str, int] = defaultdict(int)
        self.lock = anyio.Lock()

    async def increment(
        self,
        uid_document: str,
        global_document: str,
        uid_limit: int,
        global_limit: int,
    ) -> str:
        async with self.lock:
            if self.counts[uid_document] >= uid_limit:
                return "uid_exceeded"
            if self.counts[global_document] >= global_limit:
                return "global_exceeded"
            self.counts[uid_document] += 1
            self.counts[global_document] += 1
            return "allowed"


@pytest.mark.anyio
@pytest.mark.parametrize(("route", "allowed"), [("recipes", 20), ("advice", 50)])
async def test_concurrent_uid_quota_rejects_exact_next_request(route: str, allowed: int) -> None:
    from app.quota import QuotaExceeded, QuotaService

    # Given: an atomic backend and simultaneous requests for one UID.
    backend = AtomicCounterBackend()
    service = QuotaService(backend=backend, hash_key=b"test-hmac-key")
    outcomes: list[str] = []

    async def consume() -> None:
        try:
            await service.consume_at("uid-1", route, datetime(2026, 7, 18, tzinfo=UTC))
            outcomes.append("allowed")
        except QuotaExceeded:
            outcomes.append("rejected")

    # When: limit plus one requests race.
    async with anyio.create_task_group() as group:
        for _ in range(allowed + 1):
            group.start_soon(consume)

    # Then: exactly the 21st/51st request is rejected atomically.
    assert outcomes.count("allowed") == allowed
    assert outcomes.count("rejected") == 1


@pytest.mark.anyio
@pytest.mark.parametrize(("route", "global_limit"), [("recipes", 2000), ("advice", 5000)])
async def test_global_quota_is_actual_cost_boundary(route: str, global_limit: int) -> None:
    from app.quota import QuotaExceeded, QuotaService

    # Given: distinct installations sharing one route/day global document.
    backend = AtomicCounterBackend()
    service = QuotaService(backend=backend, hash_key=b"test-hmac-key")
    now = datetime(2026, 7, 18, tzinfo=UTC)

    # When: the global limit is consumed with each UID below its own cap.
    for index in range(global_limit):
        uid = f"uid-{index // 10}"
        await service.consume_at(uid, route, now)

    # Then: the next distinct installation is rejected.
    with pytest.raises(QuotaExceeded) as raised:
        await service.consume_at("last-uid", route, now)
    assert raised.value.scope == "global"


def test_retry_after_targets_next_utc_midnight() -> None:
    from app.quota import seconds_until_next_utc_day

    # Given: a timezone-aware instant near UTC midnight.
    now = datetime(2026, 7, 18, 23, 59, 58, 250_000, tzinfo=UTC)
    # When/Then: Retry-After rounds up to the next whole second.
    assert seconds_until_next_utc_day(now) == 2


def test_retry_after_converts_non_utc_instants_before_day_boundary() -> None:
    from app.quota import seconds_until_next_utc_day

    # Given: the same instant represented in Korea Standard Time.
    now = datetime(2026, 7, 19, 8, 59, 58, 250_000, tzinfo=timezone(timedelta(hours=9)))
    # When/Then: the service still targets UTC midnight rather than local midnight.
    assert seconds_until_next_utc_day(now) == 2


def test_uid_document_id_is_hmac_sha256_not_raw_uid() -> None:
    from app.quota import hashed_uid

    # Given: a raw Firebase UID and HMAC key.
    result = hashed_uid("private-uid", b"test-key")
    # When/Then: the stable document ID is a 64-char lowercase digest.
    assert len(result) == 64
    assert result != "private-uid"
    assert set(result) <= set("0123456789abcdef")
