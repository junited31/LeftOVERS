from __future__ import annotations

import base64
from dataclasses import dataclass
from typing import Literal, Protocol

from openai import APIError, AsyncOpenAI
from pydantic import BaseModel, ValidationError

from .models import CookingAdviceResponse, RecipeGenerateResponse
from .prompts import ADVICE_INSTRUCTIONS, RECIPE_INSTRUCTIONS

MODEL: Literal["gpt-5.6"] = "gpt-5.6"


class OpenAIUpstreamError(Exception):
    pass


@dataclass(frozen=True, slots=True)
class OpenAIRequest:
    model: Literal["gpt-5.6"]
    store: Literal[False]
    instructions: str
    user_data: str
    schema_name: Literal["recipe_set", "cooking_advice"]
    response_model: type[BaseModel]
    image: bytes | None = None
    image_content_type: str | None = None


class ResponsesTransport(Protocol):
    async def execute(self, request: OpenAIRequest) -> str: ...


class OpenAIResponsesTransport:
    def __init__(self, api_key: str) -> None:
        self._client = AsyncOpenAI(api_key=api_key)

    async def execute(self, request: OpenAIRequest) -> str:
        input_data: str | list[dict[str, str | list[dict[str, str]]]]
        if request.image is None:
            input_data = request.user_data
        else:
            encoded = base64.b64encode(request.image).decode("ascii")
            media_type = request.image_content_type or "image/jpeg"
            input_data = [
                {
                    "role": "user",
                    "content": [
                        {"type": "input_text", "text": request.user_data},
                        {
                            "type": "input_image",
                            "image_url": f"data:{media_type};base64,{encoded}",
                            "detail": "low",
                        },
                    ],
                }
            ]
        try:
            response = await self._client.responses.parse(
                model=request.model,
                store=request.store,
                instructions=request.instructions,
                input=input_data,
                text_format=request.response_model,
            )
        except ValidationError:
            return "{}"
        except APIError as error:
            raise OpenAIUpstreamError from error
        parsed = response.output_parsed
        return parsed.model_dump_json(by_alias=True) if parsed is not None else "{}"


class GPT56Adapter:
    def __init__(self, transport: ResponsesTransport) -> None:
        self._transport = transport

    async def generate_recipes(self, request_json: str, attempt: int) -> str:
        del attempt
        return await self._transport.execute(
            OpenAIRequest(
                model=MODEL,
                store=False,
                instructions=RECIPE_INSTRUCTIONS,
                user_data=request_json,
                schema_name="recipe_set",
                response_model=RecipeGenerateResponse,
            )
        )

    async def cooking_advice(
        self,
        request_json: str,
        photo: bytes,
        content_type: str,
        attempt: int,
    ) -> str:
        del attempt
        return await self._transport.execute(
            OpenAIRequest(
                model=MODEL,
                store=False,
                instructions=ADVICE_INSTRUCTIONS,
                user_data=request_json,
                schema_name="cooking_advice",
                response_model=CookingAdviceResponse,
                image=photo,
                image_content_type=content_type,
            )
        )
