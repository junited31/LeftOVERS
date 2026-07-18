from __future__ import annotations

import hashlib
import unicodedata
from datetime import date
from enum import StrEnum
from typing import Literal
from uuid import UUID

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field


class ApiModel(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid", populate_by_name=True)


class CanonicalUnit(StrEnum):
    GRAM = "g"
    MILLILITER = "ml"
    COUNT = "count"


class PantryRow(ApiModel):
    pantry_item_id: UUID = Field(alias="pantryItemId")
    version: int = Field(ge=0)
    name: str = Field(min_length=1, max_length=200)
    unit: CanonicalUnit
    quantity_milli_units: int = Field(alias="quantityMilliUnits", gt=0)
    expiry_date: date | None = Field(default=None, alias="expiryDate")


class RecipeHistoryHint(ApiModel):
    fingerprint: str = Field(min_length=64, max_length=64)
    cuisine: str = Field(min_length=1, max_length=100)
    primary_technique: str = Field(alias="primaryTechnique", min_length=1, max_length=100)
    ingredient_names: tuple[str, ...] = Field(alias="ingredientNames", max_length=200)
    rating: int = Field(ge=1, le=5)
    recommend_again: bool = Field(alias="recommendAgain")
    completed_at: AwareDatetime = Field(alias="completedAt")


class RecipeGenerateRequest(ApiModel):
    pantry: tuple[PantryRow, ...] = Field(min_length=1, max_length=200)
    equipment: tuple[str, ...] = Field(min_length=1, max_length=32)
    history: tuple[RecipeHistoryHint, ...] = Field(default=(), max_length=20)
    notes: str | None = Field(default=None, max_length=32_000)


class TrackedUse(ApiModel):
    pantry_item_id: UUID = Field(alias="pantryItemId")
    version: int = Field(ge=0)
    unit: CanonicalUnit
    proposed_milli_units: int = Field(alias="proposedMilliUnits", gt=0)


class MissingIngredient(ApiModel):
    name: str = Field(min_length=1, max_length=200)
    amount_milli_units: int = Field(alias="amountMilliUnits", gt=0)
    unit: CanonicalUnit


class RecipeCandidate(ApiModel):
    title: str = Field(min_length=1, max_length=200)
    cuisine: str = Field(min_length=1, max_length=100)
    primary_technique: str = Field(alias="primaryTechnique", min_length=1, max_length=100)
    required_equipment: tuple[str, ...] = Field(alias="requiredEquipment", max_length=32)
    tracked_uses: tuple[TrackedUse, ...] = Field(alias="trackedUses")
    missing_ingredients: tuple[MissingIngredient, ...] = Field(alias="missingIngredients")
    steps: tuple[str, ...] = Field(min_length=1, max_length=30)


class RecipeGenerateResponse(ApiModel):
    recipes: tuple[RecipeCandidate, ...] = Field(min_length=3, max_length=3)


class AdviceContext(ApiModel):
    step: str = Field(min_length=1, max_length=2_000)
    notes: str | None = Field(default=None, max_length=4_000)


class CookingAdviceResponse(ApiModel):
    status: Literal["continue", "adjust", "stop"]
    observations: tuple[str, ...] = Field(min_length=1, max_length=12)
    next_actions: tuple[str, ...] = Field(alias="nextActions", min_length=1, max_length=12)
    confidence: float = Field(ge=0, le=1)
    safety_note: str = Field(alias="safetyNote", min_length=1, max_length=1_000)


class ErrorDetail(ApiModel):
    code: str
    message: str


class ErrorResponse(ApiModel):
    error: ErrorDetail


class HealthResponse(ApiModel):
    status: Literal["ok"] = "ok"


class ModelContractError(Exception):
    pass


def normalize_label(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).split()).lower()


def recipe_fingerprint(
    recipe: RecipeCandidate,
    pantry: dict[UUID, PantryRow],
) -> str:
    ingredient_names = [pantry[use.pantry_item_id].name for use in recipe.tracked_uses]
    ingredient_names.extend(item.name for item in recipe.missing_ingredients)
    canonical = "|".join(
        [
            normalize_label(recipe.cuisine),
            normalize_label(recipe.primary_technique),
            *sorted(normalize_label(name) for name in ingredient_names),
        ]
    )
    return hashlib.sha256(canonical.encode()).hexdigest()


def validate_recipe_bindings(
    response: RecipeGenerateResponse,
    request: RecipeGenerateRequest,
) -> None:
    pantry = {row.pantry_item_id: row for row in request.pantry}
    titles: set[str] = set()
    fingerprints: set[str] = set()
    cuisines: set[str] = set()
    techniques: set[str] = set()
    equipment = {normalize_label(item) for item in request.equipment}
    for recipe in response.recipes:
        title = normalize_label(recipe.title)
        cuisine = normalize_label(recipe.cuisine)
        technique = normalize_label(recipe.primary_technique)
        if not title or not cuisine or not technique or title in titles:
            raise ModelContractError
        titles.add(title)
        cuisines.add(cuisine)
        techniques.add(technique)
        if any(normalize_label(item) not in equipment for item in recipe.required_equipment):
            raise ModelContractError
        seen: set[UUID] = set()
        for use in recipe.tracked_uses:
            if use.pantry_item_id in seen:
                raise ModelContractError
            seen.add(use.pantry_item_id)
            row = pantry.get(use.pantry_item_id)
            if row is None:
                raise ModelContractError
            if (
                use.version != row.version
                or use.unit != row.unit
                or use.proposed_milli_units > row.quantity_milli_units
            ):
                raise ModelContractError
        fingerprint = recipe_fingerprint(recipe, pantry)
        if fingerprint in fingerprints:
            raise ModelContractError
        fingerprints.add(fingerprint)
    if len(cuisines) < 2 or len(techniques) < 2:
        raise ModelContractError
