from __future__ import annotations

from copy import deepcopy

import pytest

from conftest import (
    FakeAI,
    JsonObject,
    UNKNOWN_PANTRY_ID,
    configured_app,
    post_json,
    recipe_request,
    valid_recipe_json,
    valid_recipe_payload,
)


def invalid_outputs() -> list[tuple[str, JsonObject]]:
    base = valid_recipe_payload()
    duplicate_id = deepcopy(base)
    duplicate_id["recipes"][0]["trackedUses"].append(
        deepcopy(duplicate_id["recipes"][0]["trackedUses"][0])
    )
    unknown_id = deepcopy(base)
    unknown_id["recipes"][0]["trackedUses"][0]["pantryItemId"] = UNKNOWN_PANTRY_ID
    version = deepcopy(base)
    version["recipes"][0]["trackedUses"][0]["version"] = 99
    unit = deepcopy(base)
    unit["recipes"][0]["trackedUses"][0]["unit"] = "ml"
    amount = deepcopy(base)
    amount["recipes"][0]["trackedUses"][0]["proposedMilliUnits"] = 500_001
    duplicate_recipes = deepcopy(base)
    duplicate_recipes["recipes"][1]["title"] = duplicate_recipes["recipes"][0]["title"]
    return [
        ("duplicate pantry id", duplicate_id),
        ("unknown pantry id", unknown_id),
        ("version mismatch", version),
        ("unit mismatch", unit),
        ("amount mismatch", amount),
        ("duplicate recipes", duplicate_recipes),
    ]


@pytest.mark.anyio
@pytest.mark.parametrize(("case", "bad_payload"), invalid_outputs())
async def test_invalid_recipe_binding_exhausts_exactly_two_retries(
    case: str,
    bad_payload: JsonObject,
) -> None:
    del case
    import json

    # Given: every model attempt returns an invalid set.
    ai = FakeAI(recipe_outputs=[json.dumps(bad_payload)])
    with configured_app(ai=ai) as (app, _, _, _):
        # When: generation is requested.
        response = await post_json(app, recipe_request())

    # Then: one initial call plus exactly two validation retries end in typed 422.
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "model_validation_failed"
    assert ai.recipe_calls == 3


@pytest.mark.anyio
@pytest.mark.parametrize("repair_attempt", [1, 2])
async def test_recipe_set_succeeds_when_either_retry_repairs_it(repair_attempt: int) -> None:
    # Given: invalid schema responses followed by a valid set on a retry.
    outputs = ["not-json", "not-json", "not-json"]
    outputs[repair_attempt] = valid_recipe_json()
    ai = FakeAI(recipe_outputs=outputs)
    with configured_app(ai=ai) as (app, _, _, _):
        # When: generation is requested.
        response = await post_json(app, recipe_request())

    # Then: the repaired set is returned without extra attempts.
    assert response.status_code == 200
    assert len(response.json()["recipes"]) == 3
    assert ai.recipe_calls == repair_attempt + 1


@pytest.mark.anyio
async def test_response_never_binds_unrequested_pantry_row() -> None:
    # Given: a model response that references an unrequested UUID.
    payload = valid_recipe_payload()
    payload["recipes"][0]["trackedUses"][0]["pantryItemId"] = UNKNOWN_PANTRY_ID
    import json

    ai = FakeAI(recipe_outputs=[json.dumps(payload)])
    with configured_app(ai=ai) as (app, _, _, _):
        # When: generation is requested.
        response = await post_json(app, recipe_request())

    # Then: the response fails closed rather than leaking the binding.
    assert response.status_code == 422
    body = response.json()
    assert UNKNOWN_PANTRY_ID not in str(body)
