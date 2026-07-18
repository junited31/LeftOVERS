from __future__ import annotations

import hashlib
import hmac
import math
from dataclasses import dataclass
from datetime import UTC, datetime, time, timedelta
from typing import Literal, Protocol, assert_never

import anyio
from google.cloud import firestore
from google.cloud.firestore import Client

QuotaRoute = Literal["recipes", "advice"]
CounterResult = Literal["allowed", "uid_exceeded", "global_exceeded"]


class CounterBackend(Protocol):
    async def increment(
        self,
        uid_document: str,
        global_document: str,
        uid_limit: int,
        global_limit: int,
    ) -> CounterResult: ...


@dataclass(frozen=True, slots=True)
class QuotaExceeded(Exception):
    scope: Literal["uid", "global"]
    retry_after: int

    def __str__(self) -> str:
        return f"{self.scope} quota exceeded"


def hashed_uid(uid: str, key: bytes) -> str:
    return hmac.new(key, uid.encode(), hashlib.sha256).hexdigest()


def seconds_until_next_utc_day(now: datetime) -> int:
    utc_now = now.astimezone(UTC)
    tomorrow = datetime.combine(utc_now.date() + timedelta(days=1), time.min, tzinfo=UTC)
    return max(1, math.ceil((tomorrow - utc_now).total_seconds()))


class QuotaService:
    def __init__(self, backend: CounterBackend, hash_key: bytes) -> None:
        self._backend = backend
        self._hash_key = hash_key

    async def consume(self, uid: str, route: QuotaRoute) -> None:
        await self.consume_at(uid, route, datetime.now(UTC))

    async def consume_at(self, uid: str, route: QuotaRoute, now: datetime) -> None:
        uid_limit, global_limit = (20, 2_000) if route == "recipes" else (50, 5_000)
        day = now.astimezone(UTC).date().isoformat()
        result = await self._backend.increment(
            f"{hashed_uid(uid, self._hash_key)}_{route}_{day}",
            f"{route}_{day}",
            uid_limit,
            global_limit,
        )
        match result:
            case "allowed":
                return
            case "uid_exceeded":
                raise QuotaExceeded("uid", seconds_until_next_utc_day(now))
            case "global_exceeded":
                raise QuotaExceeded("global", seconds_until_next_utc_day(now))
            case unreachable:
                assert_never(unreachable)


class FirestoreCounterBackend:
    def __init__(self, client: Client) -> None:
        self._client = client

    async def increment(
        self,
        uid_document: str,
        global_document: str,
        uid_limit: int,
        global_limit: int,
    ) -> CounterResult:
        return await anyio.to_thread.run_sync(
            self._increment_sync,
            uid_document,
            global_document,
            uid_limit,
            global_limit,
        )

    def _increment_sync(
        self,
        uid_document: str,
        global_document: str,
        uid_limit: int,
        global_limit: int,
    ) -> CounterResult:
        uid_ref = self._client.collection("quotaUid").document(uid_document)
        global_ref = self._client.collection("quotaGlobal").document(global_document)
        transaction = self._client.transaction()

        @firestore.transactional
        def update(transaction) -> CounterResult:
            uid_snapshot = uid_ref.get(transaction=transaction)
            global_snapshot = global_ref.get(transaction=transaction)
            uid_count = int(uid_snapshot.get("count")) if uid_snapshot.exists else 0
            global_count = int(global_snapshot.get("count")) if global_snapshot.exists else 0
            if uid_count >= uid_limit:
                return "uid_exceeded"
            if global_count >= global_limit:
                return "global_exceeded"
            transaction.set(uid_ref, {"count": uid_count + 1}, merge=True)
            transaction.set(global_ref, {"count": global_count + 1}, merge=True)
            return "allowed"

        return update(transaction)
