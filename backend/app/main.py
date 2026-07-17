from typing import Literal, TypedDict

from fastapi import FastAPI


class HealthResponse(TypedDict):
    status: Literal["ok"]


app = FastAPI(title="LeftOVERS API")


@app.get("/health")
async def health() -> HealthResponse:
    return {"status": "ok"}
