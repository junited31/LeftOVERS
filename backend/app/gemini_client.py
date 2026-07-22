from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, replace
from typing import Literal, Protocol

import httpx
from google import genai
from google.genai import types
from google.genai.errors import APIError
from pydantic import BaseModel

from .models import CookingAdviceResponse, RecipeGenerateResponse
from .prompts import (
    ADVICE_CONTEXT_END,
    ADVICE_CONTEXT_START,
    ADVICE_INSTRUCTIONS,
    RECIPE_INSTRUCTIONS,
)

RecipeModel = Literal["gemini-3.1-flash-lite", "gemini-3.5-flash"]
RECIPE_PRIMARY: Literal["gemini-3.1-flash-lite"] = "gemini-3.1-flash-lite"
RECIPE_FALLBACK: Literal["gemini-3.5-flash"] = "gemini-3.5-flash"
ADVICE_PRIMARY: Literal["gemini-3.5-flash"] = "gemini-3.5-flash"
ADVICE_FALLBACK: Literal["gemini-3.1-flash-lite"] = "gemini-3.1-flash-lite"


class AIUpstreamError(Exception):
    pass


@dataclass(frozen=True, slots=True)
class RequestBudget:
    schema_slots_used: int = 0
    primary_retry_credits_remaining: int = 2
    fallback_used: bool = False
    ordinary_primary_calls: int = 0
    primary_calls: int = 0
    fallback_calls: int = 0
    provider_calls: int = 0

    def record_primary(self, *, retry: bool) -> RequestBudget:
        if self.primary_calls >= 5 or self.provider_calls >= 6:
            raise RuntimeError("provider call budget exhausted")
        if retry:
            if self.primary_retry_credits_remaining <= 0:
                raise RuntimeError("primary retry budget exhausted")
            return replace(
                self,
                primary_retry_credits_remaining=self.primary_retry_credits_remaining - 1,
                primary_calls=self.primary_calls + 1,
                provider_calls=self.provider_calls + 1,
            )
        if self.ordinary_primary_calls >= 3:
            raise RuntimeError("schema slot budget exhausted")
        return replace(
            self,
            ordinary_primary_calls=self.ordinary_primary_calls + 1,
            primary_calls=self.primary_calls + 1,
            provider_calls=self.provider_calls + 1,
        )

    def record_fallback(self) -> RequestBudget:
        if self.fallback_used or self.fallback_calls >= 1 or self.provider_calls >= 6:
            raise RuntimeError("fallback budget exhausted")
        return replace(
            self,
            fallback_used=True,
            fallback_calls=self.fallback_calls + 1,
            provider_calls=self.provider_calls + 1,
        )

    def record_schema_slot(self) -> RequestBudget:
        if self.schema_slots_used >= 3:
            raise RuntimeError("schema slot budget exhausted")
        return replace(self, schema_slots_used=self.schema_slots_used + 1)


@dataclass(frozen=True, slots=True)
class GeminiAttempt:
    model: RecipeModel
    instructions: str
    user_data: str
    response_model: type[BaseModel]
    image: bytes | None
    image_content_type: str | None
    budget_before: RequestBudget
    budget_after: RequestBudget


@dataclass(frozen=True, slots=True)
class GeminiResponse:
    text: str
    safety_blocked: bool = False


class GeminiTransport(Protocol):
    async def execute(self, request: GeminiAttempt) -> GeminiResponse: ...

    async def aclose(self) -> None: ...


def _enum_name(value: object | None) -> str:
    if value is None:
        return ""
    return str(getattr(value, "name", value)).rsplit(".", 1)[-1].upper()


def _safety_blocked(response: object) -> bool:
    prompt_feedback = getattr(response, "prompt_feedback", None)
    block_reason = _enum_name(getattr(prompt_feedback, "block_reason", None))
    if block_reason not in {
        "",
        "0",
        "BLOCK_REASON_UNSPECIFIED",
        "BLOCKED_REASON_UNSPECIFIED",
    }:
        return True
    for candidate in getattr(response, "candidates", None) or ():
        if _enum_name(getattr(candidate, "finish_reason", None)) == "SAFETY":
            return True
        if any(
            getattr(rating, "blocked", False)
            for rating in getattr(candidate, "safety_ratings", None) or ()
        ):
            return True
    return False


class VertexGeminiTransport:
    def __init__(self, project: str) -> None:
        self._client = genai.Client(
            vertexai=True,
            project=project,
            location="global",
            http_options=types.HttpOptions(
                api_version="v1",
                retry_options=types.HttpRetryOptions(attempts=1),
            ),
        )
        self._async_client = self._client.aio

    async def execute(self, request: GeminiAttempt) -> GeminiResponse:
        contents: list[str | types.Part] = [request.user_data]
        if request.image is not None:
            contents.append(
                types.Part.from_bytes(
                    data=request.image,
                    mime_type=request.image_content_type or "application/octet-stream",
                )
            )
        response = await self._async_client.models.generate_content(
            model=request.model,
            contents=contents,
            config=types.GenerateContentConfig(
                system_instruction=request.instructions,
                response_mime_type="application/json",
                response_json_schema=request.response_model.model_json_schema(by_alias=True),
            ),
        )
        if _safety_blocked(response):
            return GeminiResponse(text="", safety_blocked=True)
        return GeminiResponse(text=response.text or "")

    async def aclose(self) -> None:
        try:
            await self._async_client.aclose()
        finally:
            self._client.close()


def _is_transient(error: Exception) -> bool:
    if isinstance(error, (httpx.TimeoutException, httpx.ConnectError)):
        return True
    return isinstance(error, APIError) and error.code in {429, 500, 503}


class GeminiAdapter:
    def __init__(
        self,
        transport: GeminiTransport,
        sleeper: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self._transport = transport
        self._sleeper = sleeper

    async def generate_recipes(
        self, request_json: str, budget: RequestBudget
    ) -> tuple[str, RequestBudget]:
        return await self._generate(
            request_json=request_json,
            instructions=RECIPE_INSTRUCTIONS,
            response_model=RecipeGenerateResponse,
            image=None,
            content_type=None,
            primary=RECIPE_PRIMARY,
            fallback=RECIPE_FALLBACK,
            budget=budget,
        )

    async def cooking_advice(
        self,
        request_json: str,
        photo: bytes,
        content_type: str,
        budget: RequestBudget,
    ) -> tuple[str, RequestBudget]:
        return await self._generate(
            request_json=f"{ADVICE_CONTEXT_START}\n{request_json}\n{ADVICE_CONTEXT_END}",
            instructions=ADVICE_INSTRUCTIONS,
            response_model=CookingAdviceResponse,
            image=photo,
            content_type=content_type,
            primary=ADVICE_PRIMARY,
            fallback=ADVICE_FALLBACK,
            budget=budget,
        )

    async def _generate(
        self,
        *,
        request_json: str,
        instructions: str,
        response_model: type[BaseModel],
        image: bytes | None,
        content_type: str | None,
        primary: RecipeModel,
        fallback: RecipeModel,
        budget: RequestBudget,
    ) -> tuple[str, RequestBudget]:
        model = primary
        retry = False
        while True:
            updated = (
                budget.record_fallback()
                if model == fallback
                else budget.record_primary(retry=retry)
            )
            attempt = GeminiAttempt(
                model=model,
                instructions=instructions,
                user_data=request_json,
                response_model=response_model,
                image=image,
                image_content_type=content_type,
                budget_before=budget,
                budget_after=updated,
            )
            try:
                response = await self._transport.execute(attempt)
            except asyncio.CancelledError:
                raise
            except Exception as error:
                budget = updated
                if model == primary and _is_transient(error):
                    if budget.primary_retry_credits_remaining:
                        delay = 0.25 if budget.primary_retry_credits_remaining == 2 else 0.5
                        await self._sleeper(delay)
                        retry = True
                        continue
                    if not budget.fallback_used:
                        model = fallback
                        retry = False
                        continue
                raise AIUpstreamError from error
            budget = updated
            if response.safety_blocked:
                raise AIUpstreamError
            return response.text, budget

    async def aclose(self) -> None:
        await self._transport.aclose()
