from __future__ import annotations

from typing import Protocol

import anyio
from firebase_admin import auth as firebase_auth


class InvalidBearerToken(Exception):
    pass


class TokenVerifier(Protocol):
    async def verify(self, token: str) -> str: ...


class FirebaseTokenVerifier:
    async def verify(self, token: str) -> str:
        try:
            claims = await anyio.to_thread.run_sync(firebase_auth.verify_id_token, token)
        except (
            firebase_auth.ExpiredIdTokenError,
            firebase_auth.InvalidIdTokenError,
            firebase_auth.RevokedIdTokenError,
            firebase_auth.UserDisabledError,
        ) as error:
            raise InvalidBearerToken from error
        uid = claims.get("uid")
        if not isinstance(uid, str) or not uid:
            raise InvalidBearerToken
        return uid
