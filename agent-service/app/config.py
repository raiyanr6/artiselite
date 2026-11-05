"""Settings for the agent service, read once at startup from environment variables (or agent-service/.env)."""
import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

# Only agent-service/.env, by explicit path. A bare load_dotenv() searches parent folders and
# silently picked up the Spring project's .env. Real environment variables still win over the
# file, so Docker/production can override it.
load_dotenv(Path(__file__).resolve().parent.parent / ".env")


@dataclass(frozen=True)
class Settings:
    openai_api_key: str
    openai_model: str
    spring_base_url: str      # where the existing Spring API lives, e.g. http://localhost:8090/api
    frontend_origin: str      # the React app's address, allowed by CORS
    max_tool_rounds: int      # tool calls allowed per user message before the agent must stop (like the Java cap of 5)
    http_timeout_seconds: float


def _load() -> Settings:
    return Settings(
        openai_api_key=os.getenv("OPENAI_API_KEY", "").strip(),
        openai_model=os.getenv("OPENAI_MODEL", "gpt-4.1-mini"),
        spring_base_url=os.getenv("SPRING_BASE_URL", "http://localhost:8090/api").rstrip("/"),
        frontend_origin=os.getenv("FRONTEND_ORIGIN", "http://localhost:5173"),
        max_tool_rounds=int(os.getenv("AGENT_MAX_TOOL_ROUNDS", "5")),
        http_timeout_seconds=float(os.getenv("HTTP_TIMEOUT_SECONDS", "15")),
    )


settings = _load()
