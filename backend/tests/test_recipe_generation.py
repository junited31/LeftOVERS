from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path

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
    two_recipes = deepcopy(base)
    two_recipes["recipes"].pop()
    missing_equipment = deepcopy(base)
    missing_equipment["recipes"][0]["requiredEquipment"] = ["oven"]
    duplicate_fingerprint = deepcopy(base)
    duplicate_fingerprint["recipes"][1]["cuisine"] = " Korean "
    duplicate_fingerprint["recipes"][1]["primaryTechnique"] = "STIR-FRY"
    duplicate_fingerprint["recipes"][1]["trackedUses"] = deepcopy(
        duplicate_fingerprint["recipes"][0]["trackedUses"]
    )
    duplicate_fingerprint["recipes"][1]["missingIngredients"] = []
    duplicate_fingerprint["recipes"][2]["cuisine"] = "Japanese"
    one_cuisine = deepcopy(base)
    for recipe in one_cuisine["recipes"]:
        recipe["cuisine"] = "Korean"
    one_technique = deepcopy(base)
    for recipe in one_technique["recipes"]:
        recipe["primaryTechnique"] = "pan-fry"
    return [
        ("duplicate pantry id", duplicate_id),
        ("unknown pantry id", unknown_id),
        ("version mismatch", version),
        ("unit mismatch", unit),
        ("amount mismatch", amount),
        ("duplicate recipes", duplicate_recipes),
        ("two recipes", two_recipes),
        ("missing equipment", missing_equipment),
        ("duplicate fingerprint", duplicate_fingerprint),
        ("one cuisine", one_cuisine),
        ("one technique", one_technique),
    ]


@pytest.mark.anyio
@pytest.mark.parametrize(("case", "bad_payload"), invalid_outputs())
async def test_invalid_recipe_binding_exhausts_exactly_two_retries(
    case: str,
    bad_payload: JsonObject,
) -> None:
    del case
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
    ai = FakeAI(recipe_outputs=[json.dumps(payload)])
    with configured_app(ai=ai) as (app, _, _, _):
        # When: generation is requested.
        response = await post_json(app, recipe_request())

    # Then: the response fails closed rather than leaking the binding.
    assert response.status_code == 422
    body = response.json()
    assert UNKNOWN_PANTRY_ID not in str(body)


def test_recipe_prompt_contains_one_compact_schema_valid_three_candidate_example() -> None:
    from app.models import RecipeGenerateRequest, RecipeGenerateResponse, validate_recipe_bindings
    from app.prompts import RECIPE_EXAMPLE_JSON, RECIPE_INSTRUCTIONS

    # Given: the machine-readable example embedded in the recipe instructions.
    request = RecipeGenerateRequest.model_validate(recipe_request())

    # When: the example is parsed by the same boundary and binding validator as a model response.
    response = RecipeGenerateResponse.model_validate_json(RECIPE_EXAMPLE_JSON)
    validate_recipe_bindings(response, request)

    # Then: it is one compact valid three-candidate set included verbatim in the instructions.
    assert len(response.recipes) == 3
    assert RECIPE_INSTRUCTIONS.count(RECIPE_EXAMPLE_JSON) == 1
    assert len(RECIPE_EXAMPLE_JSON) < 2_500


def test_shared_normalization_fixtures_match_android_values_and_fingerprint() -> None:
    from app.models import (
        CanonicalUnit,
        MissingIngredient,
        RecipeCandidate,
        normalize_label,
        recipe_fingerprint,
    )

    # Given: one cross-runtime normalization and fingerprint contract.
    fixture_path = (
        Path(__file__).resolve().parents[2]
        / ".omo/evidence/leftovers/task-6-normalization-fixtures.json"
    )
    fixtures = json.loads(fixture_path.read_text(encoding="utf-8"))

    # When: the backend canonicalizer consumes every shared edge value.
    normalized = [normalize_label(case["input"]) for case in fixtures["normalization"]]
    fingerprint = fixtures["fingerprint"]
    candidate = RecipeCandidate(
        title="fixture",
        cuisine=fingerprint["cuisine"],
        primaryTechnique=fingerprint["primaryTechnique"],
        requiredEquipment=(),
        trackedUses=(),
        missingIngredients=tuple(
            MissingIngredient(name=name, amountMilliUnits=1, unit=CanonicalUnit.COUNT)
            for name in fingerprint["ingredientNames"]
        ),
        steps=("fixture",),
    )

    # Then: values and composed SHA-256 exactly match the Android fixture expectations.
    assert normalized == [case["expected"] for case in fixtures["normalization"]]
    assert recipe_fingerprint(candidate, {}) == fingerprint["expectedSha256"]
