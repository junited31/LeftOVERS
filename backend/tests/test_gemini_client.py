from __future__ import annotations

import asyncio
from dataclasses import FrozenInstanceError
import inspect
import json
from pathlib import Path
import socket
from types import SimpleNamespace

import httpx
import pytest

from conftest import (
    FakeQuota,
    FakeVerifier,
    configured_app,
    post_json,
    recipe_request,
    valid_advice_json,
    valid_recipe_json,
    valid_recipe_payload,
)


@pytest.fixture(autouse=True)
def block_outbound_network(monkeypatch: pytest.MonkeyPatch) -> None:
    def blocked(*_args, **_kwargs):
        raise AssertionError("Task 7 tests prohibit outbound network access")

    monkeypatch.setattr(socket, "create_connection", blocked)
    monkeypatch.setattr(socket, "getaddrinfo", blocked)


def gemini_module():
    from app import gemini_client

    return gemini_client


def api_error(code: int) -> Exception:
    from google.genai.errors import APIError

    return APIError(code, {"error": {"message": "synthetic provider error"}})


class FakeTransport:
    def __init__(self, outcomes: list[object]) -> None:
        self.outcomes = list(outcomes)
        self.requests: list[object] = []
        self.closed = 0

    async def execute(self, request):
        self.requests.append(request)
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, BaseException):
            raise outcome
        response_type = gemini_module().GeminiResponse
        return outcome if isinstance(outcome, response_type) else response_type(text=outcome)

    async def aclose(self) -> None:
        self.closed += 1


class RecordingSleeper:
    def __init__(self) -> None:
        self.delays: list[float] = []

    async def __call__(self, delay: float) -> None:
        self.delays.append(delay)


@pytest.mark.anyio
async def test_primary_success_uses_one_request_owned_immutable_budget() -> None:
    gemini = gemini_module()
    transport = FakeTransport([valid_recipe_json()])
    adapter = gemini.GeminiAdapter(transport, RecordingSleeper())
    before = gemini.RequestBudget()

    raw, after = await adapter.generate_recipes(json.dumps(recipe_request()), before)

    assert raw == valid_recipe_json()
    assert before is not after
    assert before.provider_calls == 0
    assert after.primary_calls == after.ordinary_primary_calls == after.provider_calls == 1
    assert after.fallback_calls == 0
    with pytest.raises(FrozenInstanceError):
        after.provider_calls = 9
    attempt = transport.requests[0]
    assert attempt.budget_before is before
    assert attempt.budget_after is after
    assert attempt.model == "gemini-3.1-flash-lite"
    assert json.loads(attempt.user_data)["recipeKind"] == "meal"


@pytest.mark.anyio
async def test_advice_uses_fixed_model_image_bytes_mime_and_alias_model() -> None:
    gemini = gemini_module()
    transport = FakeTransport([valid_advice_json()])
    adapter = gemini.GeminiAdapter(transport, RecordingSleeper())

    raw, after = await adapter.cooking_advice(
        '{"step":"표면 확인"}', b"synthetic-png", "image/png", gemini.RequestBudget()
    )

    assert raw == valid_advice_json()
    assert after.provider_calls == 1
    attempt = transport.requests[0]
    assert attempt.model == "gemini-3.5-flash"
    assert attempt.image == b"synthetic-png"
    assert attempt.image_content_type == "image/png"
    assert "nextActions" in json.dumps(attempt.response_model.model_json_schema(by_alias=True))


@pytest.mark.anyio
async def test_two_primary_transients_sleep_then_primary_succeeds() -> None:
    gemini = gemini_module()
    sleeper = RecordingSleeper()
    transport = FakeTransport(
        [httpx.TimeoutException("timeout"), httpx.ConnectError("connect"), valid_recipe_json()]
    )
    adapter = gemini.GeminiAdapter(transport, sleeper)

    _, budget = await adapter.generate_recipes("{}", gemini.RequestBudget())

    assert sleeper.delays == [0.25, 0.5]
    assert [request.model for request in transport.requests] == [
        "gemini-3.1-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-3.1-flash-lite",
    ]
    assert budget.primary_retry_credits_remaining == 0
    assert budget.primary_calls == 3
    assert budget.ordinary_primary_calls == 1
    assert budget.provider_calls == 3


@pytest.mark.anyio
@pytest.mark.parametrize("code", [429, 500, 503])
async def test_exact_api_transients_use_retry_credit(code: int) -> None:
    gemini = gemini_module()
    sleeper = RecordingSleeper()
    transport = FakeTransport([api_error(code), valid_recipe_json()])

    _, budget = await gemini.GeminiAdapter(transport, sleeper).generate_recipes(
        "{}", gemini.RequestBudget()
    )

    assert sleeper.delays == [0.25]
    assert budget.primary_calls == 2
    assert budget.primary_retry_credits_remaining == 1


@pytest.mark.anyio
@pytest.mark.parametrize("code", [400, 401, 403, 404])
async def test_non_transient_api_errors_end_without_retry_or_fallback(code: int) -> None:
    gemini = gemini_module()
    sleeper = RecordingSleeper()
    transport = FakeTransport([api_error(code)])

    with pytest.raises(gemini.AIUpstreamError):
        await gemini.GeminiAdapter(transport, sleeper).generate_recipes(
            "{}", gemini.RequestBudget()
        )

    assert sleeper.delays == []
    assert len(transport.requests) == 1
    assert all(request.model != "gemini-3.5-flash" for request in transport.requests)


@pytest.mark.anyio
async def test_third_allowed_primary_transient_invokes_fallback_once() -> None:
    gemini = gemini_module()
    sleeper = RecordingSleeper()
    transport = FakeTransport(
        [
            httpx.TimeoutException("one"),
            httpx.ConnectError("two"),
            api_error(503),
            valid_recipe_json(),
        ]
    )

    _, budget = await gemini.GeminiAdapter(transport, sleeper).generate_recipes(
        "{}", gemini.RequestBudget()
    )

    assert sleeper.delays == [0.25, 0.5]
    assert [request.model for request in transport.requests] == [
        "gemini-3.1-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-3.5-flash",
    ]
    assert budget.primary_calls == 3
    assert budget.fallback_used is True
    assert budget.fallback_calls == 1
    assert budget.provider_calls == 4


@pytest.mark.anyio
async def test_fallback_transport_failure_makes_no_further_call() -> None:
    gemini = gemini_module()
    transport = FakeTransport(
        [
            httpx.TimeoutException("one"),
            httpx.ConnectError("two"),
            api_error(503),
            httpx.TimeoutException("fallback"),
        ]
    )

    with pytest.raises(gemini.AIUpstreamError):
        await gemini.GeminiAdapter(transport, RecordingSleeper()).generate_recipes(
            "{}", gemini.RequestBudget()
        )

    assert len(transport.requests) == 4
    assert transport.requests[-1].budget_after.fallback_used is True


@pytest.mark.anyio
async def test_safety_metadata_ends_without_retry_fallback_or_json_use() -> None:
    gemini = gemini_module()
    blocked = gemini.GeminiResponse(text="not-json", safety_blocked=True)
    transport = FakeTransport([blocked])

    with pytest.raises(gemini.AIUpstreamError):
        await gemini.GeminiAdapter(transport, RecordingSleeper()).generate_recipes(
            "{}", gemini.RequestBudget()
        )

    assert len(transport.requests) == 1
    assert transport.requests[0].model == "gemini-3.1-flash-lite"


@pytest.mark.anyio
async def test_parse_three_schema_invalid_primary_slots_caps_at_three() -> None:
    gemini = gemini_module()
    from app.main import ModelValidationFailed, parse_model_output
    from app.models import RecipeGenerateRequest, RecipeGenerateResponse

    transport = FakeTransport(["{}", "{}", "{}"])
    adapter = gemini.GeminiAdapter(transport, RecordingSleeper())
    request = RecipeGenerateRequest.model_validate(recipe_request())

    with pytest.raises(ModelValidationFailed) as raised:
        await parse_model_output(
            lambda budget: adapter.generate_recipes(
                request.model_dump_json(by_alias=True), budget
            ),
            RecipeGenerateResponse,
            gemini.RequestBudget(),
            request,
        )

    budget = raised.value.budget
    assert budget.schema_slots_used == 3
    assert budget.ordinary_primary_calls == budget.primary_calls == 3
    assert budget.fallback_calls == 0
    assert budget.provider_calls == 3


@pytest.mark.anyio
async def test_schema_invalid_fallback_is_consumed_then_primary_repairs() -> None:
    gemini = gemini_module()
    from app.main import parse_model_output
    from app.models import RecipeGenerateRequest, RecipeGenerateResponse

    transport = FakeTransport(
        [
            httpx.TimeoutException("one"),
            httpx.ConnectError("two"),
            api_error(503),
            "{}",
            valid_recipe_json(),
        ]
    )
    adapter = gemini.GeminiAdapter(transport, RecordingSleeper())
    request = RecipeGenerateRequest.model_validate(recipe_request())

    result, budget = await parse_model_output(
        lambda current: adapter.generate_recipes(
            request.model_dump_json(by_alias=True), current
        ),
        RecipeGenerateResponse,
        gemini.RequestBudget(),
        request,
    )

    assert len(result.recipes) == 3
    assert [attempt.model for attempt in transport.requests] == [
        "gemini-3.1-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-3.5-flash",
        "gemini-3.1-flash-lite",
    ]
    assert budget.schema_slots_used == 2
    assert budget.primary_calls == 4
    assert budget.ordinary_primary_calls == 2
    assert budget.fallback_calls == 1
    assert budget.provider_calls == 5


@pytest.mark.anyio
async def test_fallback_invalid_then_remaining_invalid_primary_hits_total_cap() -> None:
    gemini = gemini_module()
    from app.main import ModelValidationFailed, parse_model_output
    from app.models import RecipeGenerateRequest, RecipeGenerateResponse

    transport = FakeTransport(
        [
            httpx.TimeoutException("one"),
            httpx.ConnectError("two"),
            api_error(503),
            "{}",
            "{}",
            "{}",
        ]
    )
    adapter = gemini.GeminiAdapter(transport, RecordingSleeper())
    request = RecipeGenerateRequest.model_validate(recipe_request())

    with pytest.raises(ModelValidationFailed) as raised:
        await parse_model_output(
            lambda current: adapter.generate_recipes(
                request.model_dump_json(by_alias=True), current
            ),
            RecipeGenerateResponse,
            gemini.RequestBudget(),
            request,
        )

    budget = raised.value.budget
    assert budget.schema_slots_used == 3
    assert budget.ordinary_primary_calls == 3
    assert budget.primary_calls == 5
    assert budget.fallback_calls == 1
    assert budget.provider_calls == 6
    assert [attempt.model for attempt in transport.requests].count("gemini-3.5-flash") == 1


@pytest.mark.anyio
async def test_cancellation_is_reraised_without_fallback() -> None:
    gemini = gemini_module()
    transport = FakeTransport([asyncio.CancelledError()])

    with pytest.raises(asyncio.CancelledError):
        await gemini.GeminiAdapter(transport, RecordingSleeper()).generate_recipes(
            "{}", gemini.RequestBudget()
        )

    assert len(transport.requests) == 1


class InterleavedTransport:
    def __init__(self) -> None:
        self.requests: dict[str, list[object]] = {"fallback": [], "primary": []}
        self.outcomes: dict[str, list[object]] = {
            "fallback": [
                httpx.TimeoutException("one"),
                httpx.ConnectError("two"),
                api_error(503),
                valid_recipe_json(),
            ],
            "primary": [
                httpx.TimeoutException("one"),
                httpx.ConnectError("two"),
                valid_recipe_json(),
            ],
        }
        self.barrier = asyncio.Barrier(2)

    async def execute(self, request):
        name = asyncio.current_task().get_name()
        self.requests[name].append(request)
        if len(self.requests[name]) <= 3:
            await self.barrier.wait()
        outcome = self.outcomes[name].pop(0)
        if isinstance(outcome, BaseException):
            raise outcome
        return gemini_module().GeminiResponse(text=outcome)

    async def aclose(self) -> None:
        pass


@pytest.mark.anyio
async def test_identical_concurrent_requests_have_independent_budget_lineages() -> None:
    gemini = gemini_module()
    transport = InterleavedTransport()
    sleeps: dict[str, list[float]] = {"fallback": [], "primary": []}

    async def sleep(delay: float) -> None:
        sleeps[asyncio.current_task().get_name()].append(delay)
        await asyncio.sleep(0)

    adapter = gemini.GeminiAdapter(transport, sleep)
    payload = json.dumps(recipe_request(), sort_keys=True)
    first = asyncio.create_task(
        adapter.generate_recipes(payload, gemini.RequestBudget()), name="fallback"
    )
    second = asyncio.create_task(
        adapter.generate_recipes(payload, gemini.RequestBudget()), name="primary"
    )

    (_, first_budget), (_, second_budget) = await asyncio.gather(first, second)

    assert sleeps == {"fallback": [0.25, 0.5], "primary": [0.25, 0.5]}
    assert first_budget.fallback_calls == 1
    assert second_budget.fallback_calls == 0
    assert transport.requests["fallback"][0].budget_before is not (
        transport.requests["primary"][0].budget_before
    )
    assert first_budget is not second_budget
    assert not any("budget" in name for name in vars(adapter))
    assert not any("budget" in name for name in vars(transport))


@pytest.mark.anyio
async def test_interleaved_logical_requests_keep_schema_and_fallback_slots_independent() -> None:
    gemini = gemini_module()
    from app.main import ModelValidationFailed, parse_model_output
    from app.models import RecipeGenerateRequest, RecipeGenerateResponse

    class Transport:
        def __init__(self) -> None:
            self.requests: dict[str, list[object]] = {"exhausted": [], "repaired": []}
            self.outcomes: dict[str, list[object]] = {
                "exhausted": [
                    "{}",
                    httpx.TimeoutException("one"),
                    httpx.ConnectError("two"),
                    api_error(503),
                    "{}",
                    "{}",
                ],
                "repaired": ["{}", httpx.TimeoutException("one"), valid_recipe_json()],
            }
            self.barrier = asyncio.Barrier(2)

        async def execute(self, request):
            name = asyncio.current_task().get_name()
            self.requests[name].append(request)
            if len(self.requests[name]) <= 3:
                await self.barrier.wait()
            outcome = self.outcomes[name].pop(0)
            if isinstance(outcome, BaseException):
                raise outcome
            return gemini.GeminiResponse(text=outcome)

        async def aclose(self) -> None:
            pass

    sleeps: dict[str, list[float]] = {"exhausted": [], "repaired": []}

    async def sleep(delay: float) -> None:
        sleeps[asyncio.current_task().get_name()].append(delay)
        await asyncio.sleep(0)

    transport = Transport()
    adapter = gemini.GeminiAdapter(transport, sleep)
    request = RecipeGenerateRequest.model_validate(recipe_request())
    request_json = request.model_dump_json(by_alias=True)

    async def logical_request():
        return await parse_model_output(
            lambda current: adapter.generate_recipes(request_json, current),
            RecipeGenerateResponse,
            gemini.RequestBudget(),
            request,
        )

    exhausted = asyncio.create_task(logical_request(), name="exhausted")
    repaired = asyncio.create_task(logical_request(), name="repaired")
    exhausted_result, repaired_result = await asyncio.gather(
        exhausted, repaired, return_exceptions=True
    )

    assert isinstance(exhausted_result, ModelValidationFailed)
    exhausted_budget = exhausted_result.budget
    assert exhausted_budget.schema_slots_used == 3
    assert exhausted_budget.primary_calls == 5
    assert exhausted_budget.fallback_calls == 1
    assert exhausted_budget.provider_calls == 6
    repaired_response, repaired_budget = repaired_result
    assert len(repaired_response.recipes) == 3
    assert repaired_budget.schema_slots_used == 2
    assert repaired_budget.primary_calls == 3
    assert repaired_budget.fallback_calls == 0
    assert sleeps == {"exhausted": [0.25, 0.5], "repaired": [0.25]}
    assert transport.requests["exhausted"][0].budget_before is not (
        transport.requests["repaired"][0].budget_before
    )


@pytest.mark.anyio
async def test_cancelling_one_sleep_does_not_change_other_request() -> None:
    gemini = gemini_module()

    class PerTaskTransport:
        def __init__(self) -> None:
            self.calls = {"cancelled": 0, "survivor": 0}

        async def execute(self, request):
            del request
            name = asyncio.current_task().get_name()
            self.calls[name] += 1
            if self.calls[name] == 1:
                raise httpx.TimeoutException("retry")
            return gemini.GeminiResponse(text=valid_recipe_json())

        async def aclose(self) -> None:
            pass

    sleeping = asyncio.Event()

    async def sleep(delay: float) -> None:
        del delay
        if asyncio.current_task().get_name() == "cancelled":
            sleeping.set()
            await asyncio.Event().wait()

    transport = PerTaskTransport()
    adapter = gemini.GeminiAdapter(transport, sleep)
    cancelled = asyncio.create_task(
        adapter.generate_recipes("{}", gemini.RequestBudget()), name="cancelled"
    )
    survivor = asyncio.create_task(
        adapter.generate_recipes("{}", gemini.RequestBudget()), name="survivor"
    )
    await sleeping.wait()
    cancelled.cancel()

    with pytest.raises(asyncio.CancelledError):
        await cancelled
    _, survivor_budget = await survivor

    assert transport.calls == {"cancelled": 1, "survivor": 2}
    assert survivor_budget.primary_calls == 2
    assert survivor_budget.primary_retry_credits_remaining == 1


@pytest.mark.anyio
async def test_vertex_transport_uses_exact_sdk_contract_and_closes_both_clients(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    gemini = gemini_module()
    captured: dict[str, object] = {"calls": []}

    class Models:
        async def generate_content(self, **kwargs):
            captured["calls"].append(kwargs)
            return SimpleNamespace(text=valid_advice_json(), prompt_feedback=None, candidates=[])

    class AsyncClient:
        def __init__(self) -> None:
            self.models = Models()

        async def aclose(self) -> None:
            captured["async_closed"] = True

    class Client:
        def __init__(self, **kwargs) -> None:
            captured["client_kwargs"] = kwargs
            self.aio = AsyncClient()

        def close(self) -> None:
            captured["sync_closed"] = True

    monkeypatch.setattr(gemini.genai, "Client", Client)
    transport = gemini.VertexGeminiTransport("leftovers-019f706b")
    budget = gemini.RequestBudget()
    after = budget.record_primary(retry=False)
    from app.models import CookingAdviceResponse

    request = gemini.GeminiAttempt(
        model="gemini-3.5-flash",
        instructions="fixed",
        user_data='{"step":"brown"}',
        response_model=CookingAdviceResponse,
        image=b"png-bytes",
        image_content_type="image/png",
        budget_before=budget,
        budget_after=after,
    )

    response = await transport.execute(request)
    await transport.aclose()

    kwargs = captured["client_kwargs"]
    assert kwargs["vertexai"] is True
    assert kwargs["project"] == "leftovers-019f706b"
    assert kwargs["location"] == "global"
    assert "api_key" not in kwargs
    assert kwargs["http_options"].api_version == "v1"
    assert kwargs["http_options"].retry_options.attempts == 1
    call = captured["calls"][0]
    assert call["model"] == "gemini-3.5-flash"
    assert call["config"].response_mime_type == "application/json"
    schema_text = json.dumps(call["config"].response_json_schema)
    assert "nextActions" in schema_text
    part = call["contents"][1]
    assert part.inline_data.data == b"png-bytes"
    assert part.inline_data.mime_type == "image/png"
    assert response.safety_blocked is False
    assert captured["async_closed"] is captured["sync_closed"] is True


@pytest.mark.anyio
async def test_vertex_transport_still_closes_sync_client_if_async_close_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    gemini = gemini_module()
    closed: list[str] = []

    class AsyncClient:
        async def aclose(self) -> None:
            closed.append("async")
            raise RuntimeError("synthetic async close failure")

    class Client:
        def __init__(self, **kwargs) -> None:
            del kwargs
            self.aio = AsyncClient()

        def close(self) -> None:
            closed.append("sync")

    monkeypatch.setattr(gemini.genai, "Client", Client)
    transport = gemini.VertexGeminiTransport("leftovers-019f706b")

    with pytest.raises(RuntimeError):
        await transport.aclose()

    assert closed == ["async", "sync"]


@pytest.mark.anyio
async def test_transport_classifies_safety_before_reading_text(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    gemini = gemini_module()

    class BlockedResponse:
        prompt_feedback = SimpleNamespace(block_reason="SAFETY")
        candidates = []

        @property
        def text(self):
            raise AssertionError("blocked response text must not be read")

    class Models:
        async def generate_content(self, **kwargs):
            del kwargs
            return BlockedResponse()

    class Client:
        def __init__(self, **kwargs) -> None:
            del kwargs
            self.aio = SimpleNamespace(models=Models(), aclose=lambda: None)

        def close(self) -> None:
            pass

    monkeypatch.setattr(gemini.genai, "Client", Client)
    transport = gemini.VertexGeminiTransport("leftovers-019f706b")
    before = gemini.RequestBudget()
    from app.models import RecipeGenerateResponse

    result = await transport.execute(
        gemini.GeminiAttempt(
            model="gemini-3.1-flash-lite",
            instructions="fixed",
            user_data="{}",
            response_model=RecipeGenerateResponse,
            image=None,
            image_content_type=None,
            budget_before=before,
            budget_after=before.record_primary(retry=False),
        )
    )

    assert result.safety_blocked is True
    assert result.text == ""


def test_unspecified_prompt_block_reason_is_not_a_safety_block() -> None:
    gemini = gemini_module()
    from google.genai import types

    response = SimpleNamespace(
        prompt_feedback=SimpleNamespace(
            block_reason=types.BlockedReason.BLOCKED_REASON_UNSPECIFIED
        ),
        candidates=[],
    )

    assert gemini._safety_blocked(response) is False


@pytest.mark.anyio
async def test_lifespan_closes_cached_adapter(monkeypatch: pytest.MonkeyPatch) -> None:
    from app import main

    class ClosableAdapter:
        def __init__(self) -> None:
            self.closed = 0

        async def aclose(self) -> None:
            self.closed += 1

    app = main.create_app()
    adapter = ClosableAdapter()
    app.state.ai_adapter = adapter

    async with app.router.lifespan_context(app):
        pass

    assert adapter.closed == 1


@pytest.mark.anyio
async def test_concurrent_dependency_cold_start_constructs_one_cached_client(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from app import main

    transports: list[object] = []

    class Transport:
        def __init__(self, project: str) -> None:
            assert project == "leftovers-019f706b"
            transports.append(self)

        async def aclose(self) -> None:
            pass

    monkeypatch.setattr(main, "VertexGeminiTransport", Transport)
    monkeypatch.setattr(
        main,
        "get_settings",
        lambda: SimpleNamespace(google_cloud_project="leftovers-019f706b"),
    )
    app = main.create_app()
    request = SimpleNamespace(app=app)

    adapters = await asyncio.gather(*(main.get_ai_adapter(request) for _ in range(8)))

    assert len({id(adapter) for adapter in adapters}) == 1
    assert len(transports) == 1


def test_google_genai_is_exactly_pinned_and_openai_is_retained() -> None:
    requirements = (
        Path(__file__).resolve().parents[1] / "requirements.txt"
    ).read_text(encoding="utf-8").splitlines()

    assert "google-genai==2.12.1" in requirements
    assert "openai==2.46.0" in requirements
    assert (Path(__file__).resolve().parents[1] / "app/openai_client.py").is_file()


def test_vertex_project_is_required_while_legacy_openai_secret_stays_optional(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from pydantic import ValidationError
    from app.config import Settings

    monkeypatch.delenv("GOOGLE_CLOUD_PROJECT", raising=False)
    settings = Settings(
        _env_file=None,
        quota_hash_key="synthetic-hash-key",
        google_cloud_project="leftovers-019f706b",
    )
    assert settings.openai_api_key is None
    assert settings.google_cloud_project == "leftovers-019f706b"
    with pytest.raises(ValidationError):
        Settings(_env_file=None, quota_hash_key="synthetic-hash-key")


def test_deploy_contract_sets_vertex_project_and_retains_openai_secret_reference() -> None:
    script = (
        Path(__file__).resolve().parents[2] / "scripts/deploy_backend.ps1"
    ).read_text(encoding="utf-8")

    assert '"--set-env-vars=GOOGLE_CLOUD_PROJECT=$ProjectId"' in script
    assert "[switch]$BindLegacyOpenAiSecret" in script
    assert "$secretBindings = 'QUOTA_HASH_KEY=QUOTA_HASH_KEY:latest'" in script
    assert "if ($BindLegacyOpenAiSecret)" in script
    assert "OPENAI_API_KEY=OPENAI_API_KEY:latest" in script


def test_bootstrap_contract_keeps_legacy_openai_secret_opt_in() -> None:
    script = (
        Path(__file__).resolve().parents[2] / "scripts/bootstrap_cloud.ps1"
    ).read_text(encoding="utf-8")

    assert "$useLegacyOpenAi = -not [string]::IsNullOrWhiteSpace($OpenAiApiKeyFile)" in script
    assert "if ($useLegacyOpenAi -and -not $openAiVersion.Success" in script
    assert "if ($useLegacyOpenAi) {" in script
    assert "Name = 'OPENAI_API_KEY'" in script


def test_adapter_protocol_passes_budget_explicitly() -> None:
    gemini = gemini_module()
    from app.main import AIAdapter, parse_model_output

    assert "budget" in inspect.signature(AIAdapter.generate_recipes).parameters
    assert "budget" in inspect.signature(AIAdapter.cooking_advice).parameters
    assert "budget" in inspect.signature(parse_model_output).parameters
    assert not any("budget" in name for name in vars(gemini.GeminiAdapter))


@pytest.mark.anyio
@pytest.mark.parametrize(("locale", "recipe_kind"), [("en", "meal"), ("ko", "dessert")])
async def test_fake_gemini_recipe_http_happy_paths_consume_quota_once(
    locale: str,
    recipe_kind: str,
) -> None:
    gemini = gemini_module()
    payload = valid_recipe_payload(recipe_kind=recipe_kind)
    if locale == "ko":
        for index, recipe in enumerate(payload["recipes"]):
            recipe["title"] = f"한국어 디저트 {index + 1}"
            recipe["steps"] = ["재료를 익힌다"]
    transport = FakeTransport([json.dumps(payload, ensure_ascii=False)])
    adapter = gemini.GeminiAdapter(transport, RecordingSleeper())

    with configured_app(ai=adapter) as (app, _, _, quota):
        response = await post_json(
            app, recipe_request(locale=locale, recipe_kind=recipe_kind)
        )

    assert response.status_code == 200
    assert len(response.json()["recipes"]) == 3
    assert {recipe["recipeKind"] for recipe in response.json()["recipes"]} == {
        recipe_kind
    }
    outbound = json.loads(transport.requests[0].user_data)
    assert (outbound["locale"], outbound["recipeKind"]) == (locale, recipe_kind)
    assert quota.calls == [("firebase-user-1", "recipes")]


@pytest.mark.anyio
async def test_fake_gemini_png_advice_http_happy_path_consumes_quota_once() -> None:
    gemini = gemini_module()
    from httpx import ASGITransport, AsyncClient

    transport = FakeTransport([valid_advice_json()])
    adapter = gemini.GeminiAdapter(transport, RecordingSleeper())
    with configured_app(ai=adapter) as (app, _, _, quota):
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test"
        ) as client:
            response = await client.post(
                "/v1/cooking/advice",
                data={"context": '{"step":"표면을 확인한다"}'},
                files={"photo": ("synthetic.png", b"synthetic-png", "image/png")},
                headers={"Authorization": "Bearer valid-token"},
            )

    assert response.status_code == 200
    assert response.json()["nextActions"] == ["check_center_temperature"]
    assert transport.requests[0].image == b"synthetic-png"
    assert transport.requests[0].image_content_type == "image/png"
    assert quota.calls == [("firebase-user-1", "advice")]


@pytest.mark.anyio
async def test_fake_gemini_provider_error_is_typed_and_redacted(
    caplog: pytest.LogCaptureFixture,
) -> None:
    import logging

    gemini = gemini_module()
    canary = "provider-secret-canary"
    caplog.set_level(logging.INFO, logger="leftovers.api")
    transport = FakeTransport([api_error(400)])
    adapter = gemini.GeminiAdapter(transport, RecordingSleeper())

    with configured_app(ai=adapter) as (app, _, _, quota):
        response = await post_json(app, recipe_request(name=canary))

    assert response.status_code == 502
    assert response.json() == {
        "error": {"code": "upstream_error", "message": "AI service unavailable"}
    }
    assert canary not in caplog.text
    assert "synthetic provider error" not in response.text + caplog.text
    assert quota.calls == [("firebase-user-1", "recipes")]


@pytest.mark.anyio
async def test_invalid_provider_config_is_typed_without_model_or_quota_call(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from app import main

    def invalid_config():
        raise ValueError("synthetic config secret")

    app = main.create_app()
    quota = FakeQuota()
    app.dependency_overrides[main.get_token_verifier] = lambda: FakeVerifier()
    app.dependency_overrides[main.get_quota_store] = lambda: quota
    monkeypatch.setattr(main, "get_settings", invalid_config)
    try:
        response = await post_json(app, recipe_request())
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 502
    assert response.json()["error"]["code"] == "upstream_error"
    assert "synthetic config secret" not in response.text
    assert quota.calls == []
