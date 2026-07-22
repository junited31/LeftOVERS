from __future__ import annotations

from functools import lru_cache

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    openai_api_key: SecretStr | None = None
    quota_hash_key: SecretStr
    google_cloud_project: str = Field(min_length=1)


@lru_cache
def get_settings() -> Settings:
    return Settings()
