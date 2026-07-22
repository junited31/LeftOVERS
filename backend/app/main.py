from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from functools import lru_cache
from typing import Annotated, Protocol

import firebase_admin
from fastapi import Depends, FastAPI, File, Form, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from firebase_admin import firestore
from pydantic import ValidationError
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from .auth import FirebaseTokenVerifier, InvalidBearerToken, TokenVerifier
from .config import get_settings
from .gemini_client import (
    AIUpstreamError,
    GeminiAdapter,
    RequestBudget,
    VertexGeminiTransport,
)
from .models import (
    AdviceContext,
    CookingAdviceResponse,
    ErrorDetail,
    ErrorResponse,
    HealthResponse,
    ModelContractError,
    RecipeGenerateRequest,
    RecipeGenerateResponse,
    validate_recipe_bindings,
)
from .openai_client import OpenAIUpstreamError
from .quota import FirestoreCounterBackend, QuotaExceeded, QuotaService

JSON_LIMIT = 256 * 1024
PHOTO_LIMIT = 8 * 1024 * 1024
PHOTO_CHUNK = 64 * 1024
LOGGER = logging.getLogger("leftovers.api")
BEARER = HTTPBearer(auto_error=False)


class AIAdapter(Protocol):
    async def generate_recipes(
        self, request_json: str, budget: RequestBudget
    ) -> tuple[str, RequestBudget]: ...

    async def cooking_advice(
        self,
        request_json: str,
        photo: bytes,
        content_type: str,
        budget: RequestBudget,
    ) -> tuple[str, RequestBudget]: ...


class PayloadTooLarge(Exception):
    pass


class ModelValidationFailed(Exception):
    def __init__(self, budget: RequestBudget | None = None) -> None:
        self.budget = budget


def error_response(
    status_code: int,
    code: str,
    message: str,
    headers: dict[str, str] | None = None,
) -> JSONResponse:
    payload = ErrorResponse(error=ErrorDetail(code=code, message=message))
    return JSONResponse(
        content=payload.model_dump(by_alias=True),
        status_code=status_code,
        headers=headers,
    )


class JsonLimitMiddleware:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or scope.get("path") != "/v1/recipes/generate":
            await self.app(scope, receive, send)
            return
        headers = {key.lower(): value for key, value in scope.get("headers", [])}
        length = headers.get(b"content-length")
        if length is not None and length.isdigit() and int(length) > JSON_LIMIT:
            await error_response(413, "payload_too_large", "JSON body exceeds 256 KiB")(
                scope, receive, send
            )
            return
        body = bytearray()
        more = True
        while more:
            message = await receive()
            chunk = message.get("body", b"")
            if len(body) + len(chunk) > JSON_LIMIT:
                await error_response(413, "payload_too_large", "JSON body exceeds 256 KiB")(
                    scope, receive, send
                )
                return
            body.extend(chunk)
            more = bool(message.get("more_body", False))
        replayed = False

        async def replay() -> Message:
            nonlocal replayed
            if replayed:
                return {"type": "http.request", "body": b"", "more_body": False}
            replayed = True
            return {"type": "http.request", "body": bytes(body), "more_body": False}

        await self.app(scope, replay, send)


class MetadataLogMiddleware:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        status_code = 500

        async def logged_send(message: Message) -> None:
            nonlocal status_code
            if message["type"] == "http.response.start":
                status_code = message["status"]
            await send(message)

        await self.app(scope, receive, logged_send)
        LOGGER.info(
            "request_completed method=%s path=%s status=%d",
            scope.get("method"),
            scope.get("path"),
            status_code,
        )


def ensure_firebase_app() -> None:
    try:
        firebase_admin.get_app()
    except ValueError:
        firebase_admin.initialize_app()


@lru_cache
def get_token_verifier() -> TokenVerifier:
    ensure_firebase_app()
    return FirebaseTokenVerifier()


@lru_cache
def get_quota_store() -> QuotaService:
    ensure_firebase_app()
    settings = get_settings()
    return QuotaService(
        FirestoreCounterBackend(firestore.client()),
        settings.quota_hash_key.get_secret_value().encode(),
    )


async def get_ai_adapter(request: Request) -> AIAdapter:
    cached = getattr(request.app.state, "ai_adapter", None)
    if cached is not None:
        return cached
    try:
        async with request.app.state.ai_adapter_lock:
            cached = getattr(request.app.state, "ai_adapter", None)
            if cached is None:
                settings = get_settings()
                cached = GeminiAdapter(VertexGeminiTransport(settings.google_cloud_project))
                request.app.state.ai_adapter = cached
            return cached
    except Exception as error:
        raise AIUpstreamError from error


@asynccontextmanager
async def ai_lifespan(application: FastAPI) -> AsyncIterator[None]:
    try:
        yield
    finally:
        cached = getattr(application.state, "ai_adapter", None)
        if cached is not None:
            try:
                await cached.aclose()
            finally:
                del application.state.ai_adapter


async def current_uid(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(BEARER)],
    verifier: Annotated[TokenVerifier, Depends(get_token_verifier)],
) -> str:
    if credentials is None or credentials.scheme.casefold() != "bearer":
        raise InvalidBearerToken
    return await verifier.verify(credentials.credentials)


async def parse_model_output(
    operation: Callable[[RequestBudget], Awaitable[tuple[str, RequestBudget]]],
    response_model: type[RecipeGenerateResponse] | type[CookingAdviceResponse],
    budget: RequestBudget,
    request: RecipeGenerateRequest | None = None,
) -> tuple[RecipeGenerateResponse | CookingAdviceResponse, RequestBudget]:
    while budget.schema_slots_used < 3:
        raw, budget = await operation(budget)
        try:
            parsed = response_model.model_validate_json(raw)
            if isinstance(parsed, RecipeGenerateResponse):
                if request is None:
                    raise ModelContractError
                validate_recipe_bindings(parsed, request)
            return parsed, budget.record_schema_slot()
        except (ValidationError, ModelContractError):
            budget = budget.record_schema_slot()
    raise ModelValidationFailed(budget)


async def read_photo(upload: UploadFile) -> bytes:
    content = bytearray()
    while chunk := await upload.read(PHOTO_CHUNK):
        if len(content) + len(chunk) > PHOTO_LIMIT:
            raise PayloadTooLarge
        content.extend(chunk)
    return bytes(content)


def create_app() -> FastAPI:
    application = FastAPI(title="LeftOVERS API", lifespan=ai_lifespan)
    application.state.ai_adapter_lock = asyncio.Lock()
    application.add_middleware(MetadataLogMiddleware)
    application.add_middleware(JsonLimitMiddleware)

    @application.exception_handler(InvalidBearerToken)
    async def invalid_token_handler(_: Request, __: InvalidBearerToken) -> JSONResponse:
        return error_response(401, "unauthorized", "Valid Firebase bearer token required")

    @application.exception_handler(PayloadTooLarge)
    async def payload_handler(_: Request, __: PayloadTooLarge) -> JSONResponse:
        return error_response(413, "payload_too_large", "Payload exceeds service limit")

    @application.exception_handler(RequestValidationError)
    async def request_validation_handler(_: Request, __: RequestValidationError) -> JSONResponse:
        return error_response(422, "validation_error", "Request does not match schema")

    @application.exception_handler(ModelValidationFailed)
    async def model_validation_handler(_: Request, __: ModelValidationFailed) -> JSONResponse:
        return error_response(422, "model_validation_failed", "Model response failed validation")

    @application.exception_handler(OpenAIUpstreamError)
    @application.exception_handler(AIUpstreamError)
    async def upstream_handler(_: Request, __: OpenAIUpstreamError) -> JSONResponse:
        return error_response(502, "upstream_error", "AI service unavailable")

    @application.exception_handler(QuotaExceeded)
    async def quota_handler(_: Request, error: QuotaExceeded) -> JSONResponse:
        return error_response(
            429,
            "quota_exceeded",
            "Daily AI quota exhausted",
            {"Retry-After": str(error.retry_after)},
        )

    @application.get("/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        return HealthResponse()

    @application.post("/v1/recipes/generate", response_model=RecipeGenerateResponse)
    async def generate_recipes(
        uid: Annotated[str, Depends(current_uid)],
        payload: RecipeGenerateRequest,
        quota: Annotated[QuotaService, Depends(get_quota_store)],
        ai: Annotated[AIAdapter, Depends(get_ai_adapter)],
    ) -> RecipeGenerateResponse:
        await quota.consume(uid, "recipes")
        result, _ = await parse_model_output(
            lambda budget: ai.generate_recipes(
                payload.model_dump_json(by_alias=True), budget
            ),
            RecipeGenerateResponse,
            RequestBudget(),
            payload,
        )
        if not isinstance(result, RecipeGenerateResponse):
            raise ModelValidationFailed
        return result

    @application.post("/v1/cooking/advice", response_model=CookingAdviceResponse)
    async def cooking_advice(
        uid: Annotated[str, Depends(current_uid)],
        context: Annotated[str, Form()],
        photo: Annotated[UploadFile, File()],
        quota: Annotated[QuotaService, Depends(get_quota_store)],
        ai: Annotated[AIAdapter, Depends(get_ai_adapter)],
    ) -> CookingAdviceResponse:
        try:
            parsed_context = AdviceContext.model_validate_json(context)
            content_type = photo.content_type or "application/octet-stream"
            if content_type not in {"image/jpeg", "image/png", "image/webp", "image/gif"}:
                raise RequestValidationError([])
            photo_bytes = await read_photo(photo)
            await quota.consume(uid, "advice")
            result, _ = await parse_model_output(
                lambda budget: ai.cooking_advice(
                    parsed_context.model_dump_json(by_alias=True),
                    photo_bytes,
                    content_type,
                    budget,
                ),
                CookingAdviceResponse,
                RequestBudget(),
            )
            if not isinstance(result, CookingAdviceResponse):
                raise ModelValidationFailed
            return result
        except ValidationError as error:
            raise RequestValidationError([]) from error
        finally:
            await photo.close()

    return application


app = create_app()
