from __future__ import annotations

import logging

import pytest

from conftest import FakeAI, configured_app, post_json, recipe_request


@pytest.mark.anyio
async def test_invalid_token_returns_typed_401_without_model_call() -> None:
    # Given: an authenticated endpoint and an invalid bearer token.
    with configured_app() as (app, ai, _, quota):
        # When: the request is sent.
        response = await post_json(app, recipe_request(), token="expired-token")

    # Then: the boundary rejects it before quota or model work.
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "unauthorized"
    assert ai.recipe_calls == 0
    assert quota.calls == []


@pytest.mark.anyio
async def test_json_over_256_kib_returns_413_without_model_call() -> None:
    # Given: a syntactically valid request larger than the JSON ceiling.
    with configured_app() as (app, ai, _, quota):
        payload = recipe_request(notes="x" * (256 * 1024))

        # When: the request crosses the HTTP boundary.
        response = await post_json(app, payload)

    # Then: it is rejected before authentication quota consumption or model work.
    assert response.status_code == 413
    assert response.json()["error"]["code"] == "payload_too_large"
    assert ai.recipe_calls == 0
    assert quota.calls == []


@pytest.mark.anyio
async def test_pydantic_boundary_returns_typed_422_without_model_call() -> None:
    # Given: a pantry row with a non-canonical unit.
    payload = recipe_request()
    pantry = payload["pantry"]
    assert isinstance(pantry, list)
    first_row = pantry[0]
    assert isinstance(first_row, dict)
    first_row["unit"] = "kg"
    with configured_app() as (app, ai, _, quota):
        # When: it is posted.
        response = await post_json(app, payload)

    # Then: parsing fails before quota or model work.
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "validation_error"
    assert ai.recipe_calls == 0
    assert quota.calls == []


@pytest.mark.anyio
async def test_logs_redact_bearer_and_request_content(caplog: pytest.LogCaptureFixture) -> None:
    # Given: sensitive-looking user content and a bearer token.
    secret_text = "private-pantry-instruction"
    caplog.set_level(logging.INFO, logger="leftovers.api")
    with configured_app() as (app, _, _, _):
        # When: the request completes.
        response = await post_json(app, recipe_request(name=secret_text))

    # Then: logs expose only request metadata.
    assert response.status_code == 200
    log_text = caplog.text
    assert "valid-token" not in log_text
    assert secret_text not in log_text
    assert "/v1/recipes/generate" in log_text


@pytest.mark.anyio
async def test_upstream_failure_returns_typed_502_without_retry() -> None:
    from app.openai_client import OpenAIUpstreamError

    class FailingAI(FakeAI):
        async def generate_recipes(self, request_json: str, attempt: int) -> str:
            del request_json, attempt
            self.recipe_calls += 1
            raise OpenAIUpstreamError

    # Given: an upstream transport failure.
    ai = FailingAI()
    with configured_app(ai=ai) as (app, _, _, _):
        # When: generation is requested.
        response = await post_json(app, recipe_request())

    # Then: it is not retried as a validation failure.
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "upstream_error"
    assert ai.recipe_calls == 1


@pytest.mark.anyio
async def test_quota_429_has_utc_retry_after_without_model_call() -> None:
    from app.quota import QuotaExceeded

    class ExhaustedQuota:
        async def consume(self, uid: str, route: str) -> None:
            del uid, route
            raise QuotaExceeded("global", 123)

    # Given: an exhausted global daily quota.
    quota = ExhaustedQuota()
    with configured_app(quota=quota) as (app, ai, _, _):
        # When: generation is requested.
        response = await post_json(app, recipe_request())

    # Then: the typed response carries UTC-midnight Retry-After and skips the model.
    assert response.status_code == 429
    assert response.headers["Retry-After"] == "123"
    assert response.json()["error"]["code"] == "quota_exceeded"
    assert ai.recipe_calls == 0
