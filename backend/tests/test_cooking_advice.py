from __future__ import annotations

import pytest
from httpx import ASGITransport, AsyncClient
from starlette.datastructures import UploadFile

from conftest import FakeAI, configured_app


async def post_photo(app, photo: bytes, *, token: str = "valid-token"):
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        return await client.post(
            "/v1/cooking/advice",
            data={"context": '{"step":"Brown the rice"}'},
            files={"photo": ("step.jpg", photo, "image/jpeg")},
            headers={"Authorization": f"Bearer {token}"},
        )


@pytest.mark.anyio
async def test_photo_over_8_mib_returns_413_without_model_or_retained_bytes() -> None:
    # Given: a photo one byte above the service ceiling.
    with configured_app() as (app, ai, _, _):
        # When: it is uploaded.
        response = await post_photo(app, b"x" * (8 * 1024 * 1024 + 1))

    # Then: no model boundary sees or retains the bytes.
    assert response.status_code == 413
    assert response.json()["error"]["code"] == "payload_too_large"
    assert ai.advice_calls == 0
    assert ai.photo_bytes == []


@pytest.mark.anyio
async def test_invalid_token_photo_returns_401_without_model_or_retained_bytes() -> None:
    # Given: a photo and an expired token.
    with configured_app() as (app, ai, _, quota):
        # When: it is uploaded.
        response = await post_photo(app, b"jpeg", token="expired-token")

    # Then: authentication fails before quota/model state changes.
    assert response.status_code == 401
    assert ai.advice_calls == 0
    assert ai.photo_bytes == []
    assert quota.calls == []


@pytest.mark.anyio
async def test_upload_is_closed_after_success(monkeypatch: pytest.MonkeyPatch) -> None:
    # Given: an observable UploadFile close boundary.
    closed_files: list[bool] = []
    original_close = UploadFile.close

    async def observed_close(upload: UploadFile) -> None:
        await original_close(upload)
        closed_files.append(upload.file.closed)

    monkeypatch.setattr(UploadFile, "close", observed_close)
    with configured_app() as (app, _, _, _):
        # When: advice succeeds.
        response = await post_photo(app, b"jpeg")

    # Then: the server-side upload resource is closed.
    assert response.status_code == 200
    assert closed_files and all(closed_files)


@pytest.mark.anyio
@pytest.mark.parametrize(
    "photo",
    [
        pytest.param(b"x" * (8 * 1024 * 1024 + 1), id="oversize"),
        pytest.param(b"jpeg", id="invalid-model-schema"),
    ],
)
async def test_upload_is_closed_on_failure_paths(
    monkeypatch: pytest.MonkeyPatch,
    photo: bytes,
) -> None:
    # Given: observable close handling and either boundary or model-validation failure.
    closed_files: list[bool] = []
    original_close = UploadFile.close

    async def observed_close(upload: UploadFile) -> None:
        await original_close(upload)
        closed_files.append(upload.file.closed)

    monkeypatch.setattr(UploadFile, "close", observed_close)
    ai = FakeAI(advice_outputs=["not-json"])
    with configured_app(ai=ai) as (app, _, _, _):
        # When: the request fails.
        response = await post_photo(app, photo)

    # Then: both explicit endpoint cleanup and framework cleanup leave it closed.
    assert response.status_code in {413, 422}
    assert closed_files and all(closed_files)


@pytest.mark.anyio
async def test_invalid_advice_schema_retries_twice_then_422() -> None:
    # Given: three malformed model outputs.
    ai = FakeAI(advice_outputs=["not-json"])
    with configured_app(ai=ai) as (app, _, _, _):
        # When: advice is requested.
        response = await post_photo(app, b"jpeg")

    # Then: exactly two validation retries occur.
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "model_validation_failed"
    assert ai.advice_calls == 3
