from __future__ import annotations

import json
from collections.abc import Iterator
from contextlib import contextmanager
from typing import Final
import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

PANTRY_ID: Final = "10000000-0000-4000-8000-000000000001"
OTHER_PANTRY_ID: Final = "10000000-0000-4000-8000-000000000002"
UNKNOWN_PANTRY_ID: Final = "10000000-0000-4000-8000-000000000099"
type JsonScalar = str | int | float | bool | None
type JsonValue = JsonScalar | list[JsonValue] | dict[str, JsonValue]
type JsonObject = dict[str, JsonValue]


class FakeVerifier:
    def __init__(self) -> None:
        self.tokens: list[str] = []

    async def verify(self, token: str) -> str:
        self.tokens.append(token)
        if token != "valid-token":
            from app.auth import InvalidBearerToken

            raise InvalidBearerToken
        return "firebase-user-1"


class FakeQuota:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str]] = []

    async def consume(self, uid: str, route: str) -> None:
        self.calls.append((uid, route))


class FakeAI:
    def __init__(
        self,
        *,
        recipe_outputs: list[str] | None = None,
        advice_outputs: list[str] | None = None,
    ) -> None:
        self.recipe_outputs = recipe_outputs or [valid_recipe_json()]
        self.advice_outputs = advice_outputs or [valid_advice_json()]
        self.recipe_calls = 0
        self.recipe_request_jsons: list[str] = []
        self.advice_calls = 0
        self.photo_bytes: list[bytes] = []

    async def generate_recipes(self, request_json: str, budget):
        index = min(budget.schema_slots_used, len(self.recipe_outputs) - 1)
        self.recipe_calls += 1
        self.recipe_request_jsons.append(request_json)
        return self.recipe_outputs[index], budget.record_primary(retry=False)

    async def cooking_advice(
        self,
        request_json: str,
        photo: bytes,
        content_type: str,
        budget,
    ):
        del request_json, content_type
        index = min(budget.schema_slots_used, len(self.advice_outputs) - 1)
        self.advice_calls += 1
        self.photo_bytes.append(photo)
        return self.advice_outputs[index], budget.record_primary(retry=False)


def recipe_request(
    *,
    name: str = "Rice",
    notes: str | None = None,
    locale: str = "en",
    recipe_kind: str = "meal",
) -> JsonObject:
    payload: JsonObject = {
        "locale": locale,
        "recipeKind": recipe_kind,
        "pantry": [
            {
                "pantryItemId": PANTRY_ID,
                "version": 2,
                "name": name,
                "unit": "g",
                "quantityMilliUnits": 500_000,
                "expiryDate": "2026-07-20",
            },
            {
                "pantryItemId": OTHER_PANTRY_ID,
                "version": 4,
                "name": "Egg",
                "unit": "count",
                "quantityMilliUnits": 6_000,
                "expiryDate": None,
            },
        ],
        "equipment": ["gas burner", "basic cookware"],
    }
    if notes is not None:
        payload["notes"] = notes
    return payload


def valid_recipe_payload(*, recipe_kind: str = "meal") -> JsonObject:
    return {
        "recipes": [
            {
                "recipeKind": recipe_kind,
                "title": "Egg fried rice",
                "cuisine": "Korean",
                "primaryTechnique": "stir-fry",
                "requiredEquipment": ["gas burner"],
                "trackedUses": [
                    {
                        "pantryItemId": PANTRY_ID,
                        "version": 2,
                        "unit": "g",
                        "proposedMilliUnits": 200_000,
                    }
                ],
                "missingIngredients": [],
                "steps": ["Cook the rice", "Add egg"],
            },
            {
                "recipeKind": recipe_kind,
                "title": "Rice omelette",
                "cuisine": "Japanese",
                "primaryTechnique": "pan-fry",
                "requiredEquipment": ["gas burner"],
                "trackedUses": [
                    {
                        "pantryItemId": OTHER_PANTRY_ID,
                        "version": 4,
                        "unit": "count",
                        "proposedMilliUnits": 2_000,
                    }
                ],
                "missingIngredients": [
                    {"name": "Salt", "amountMilliUnits": 1_000, "unit": "g"}
                ],
                "steps": ["Beat eggs", "Fold rice"],
            },
            {
                "recipeKind": recipe_kind,
                "title": "Crispy rice cakes",
                "cuisine": "Korean",
                "primaryTechnique": "pan-fry",
                "requiredEquipment": ["gas burner"],
                "trackedUses": [
                    {
                        "pantryItemId": PANTRY_ID,
                        "version": 2,
                        "unit": "g",
                        "proposedMilliUnits": 150_000,
                    }
                ],
                "missingIngredients": [],
                "steps": ["Shape rice", "Pan fry"],
            },
        ]
    }


def valid_recipe_json() -> str:
    return json.dumps(valid_recipe_payload())


def valid_advice_json() -> str:
    return json.dumps(
        {
            "status": "continue",
            "observations": ["surface_browned"],
            "nextActions": ["check_center_temperature"],
            "confidence": 0.72,
            "safetyNote": "A photo cannot confirm doneness or food safety; verify time and temperature.",
        }
    )


@contextmanager
def configured_app(
    ai: FakeAI | None = None,
    verifier: FakeVerifier | None = None,
    quota: FakeQuota | None = None,
) -> Iterator[tuple[FastAPI, FakeAI, FakeVerifier, FakeQuota]]:
    from app import main

    selected_ai = ai or FakeAI()
    selected_verifier = verifier or FakeVerifier()
    selected_quota = quota or FakeQuota()
    application = main.create_app()
    application.dependency_overrides[main.get_ai_adapter] = lambda: selected_ai
    application.dependency_overrides[main.get_token_verifier] = lambda: selected_verifier
    application.dependency_overrides[main.get_quota_store] = lambda: selected_quota
    try:
        yield application, selected_ai, selected_verifier, selected_quota
    finally:
        application.dependency_overrides.clear()


async def post_json(app: FastAPI, payload: JsonObject, token: str = "valid-token"):
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        return await client.post(
            "/v1/recipes/generate",
            json=payload,
            headers={"Authorization": f"Bearer {token}"},
        )


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"
