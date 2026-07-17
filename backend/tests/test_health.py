import anyio
from starlette.types import Message, Scope

from app.main import app


async def request_health() -> list[Message]:
    messages: list[Message] = []

    async def receive() -> Message:
        return {"type": "http.request", "body": b"", "more_body": False}

    async def send(message: Message) -> None:
        messages.append(message)

    scope: Scope = {
        "type": "http",
        "asgi": {"version": "3.0", "spec_version": "2.3"},
        "http_version": "1.1",
        "server": ("testserver", 80),
        "client": ("testclient", 50000),
        "scheme": "http",
        "method": "GET",
        "root_path": "",
        "path": "/health",
        "raw_path": b"/health",
        "query_string": b"",
        "headers": [],
        "state": {},
    }
    await app(scope, receive, send)
    return messages


def test_application_contract_through_asgi_surface() -> None:
    # Given: the bootstrapped FastAPI application.
    # When: a client requests its health route through ASGI.
    messages = anyio.run(request_health)

    # Then: its identity and observable HTTP response are stable.
    assert app.title == "LeftOVERS API"
    assert messages[0]["status"] == 200
    assert messages[1]["body"] == b'{"status":"ok"}'
