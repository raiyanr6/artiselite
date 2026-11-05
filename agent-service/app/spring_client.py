"""The only place that talks to Spring. Every request carries the user's own JWT, so Spring
decides permissions exactly as it does for the React pages."""
from typing import Any

import httpx

from app.config import settings


class SpringError(Exception):
    """Spring answered with an error status, or could not be reached (status 0)."""

    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


class SpringClient:
    def __init__(self, token: str, transport: httpx.BaseTransport | None = None):
        # transport is only for tests: lets them answer requests without a real Spring server
        self._http = httpx.Client(
            base_url=settings.spring_base_url,
            headers={"Authorization": f"Bearer {token}"},
            timeout=settings.http_timeout_seconds,
            transport=transport,
        )

    def get(self, path: str, params: dict[str, Any] | None = None) -> Any:
        # Leave out empty parameters so Spring applies its own defaults
        clean = {k: v for k, v in (params or {}).items() if v is not None}
        return self._send("GET", path, params=clean)

    def post(self, path: str, body: dict[str, Any]) -> Any:
        return self._send("POST", path, json=body)

    def close(self) -> None:
        self._http.close()

    # "with SpringClient(token) as spring:" closes the connection automatically
    def __enter__(self) -> "SpringClient":
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    def _send(self, method: str, path: str, **kwargs: Any) -> Any:
        try:
            response = self._http.request(method, path, **kwargs)
        except httpx.HTTPError as e:
            raise SpringError(0, f"Could not reach the warehouse API ({type(e).__name__}).") from e
        if response.is_success:
            return response.json() if response.content else None
        raise SpringError(response.status_code, _error_message(response))


def _error_message(response: httpx.Response) -> str:
    """Spring's error JSON has a readable "message" (and "errors" for @Valid failures)."""
    try:
        body = response.json()
    except ValueError:
        return f"HTTP {response.status_code}"
    field_errors = [e.get("defaultMessage") for e in body.get("errors") or [] if isinstance(e, dict)]
    if field_errors:
        return "; ".join(m for m in field_errors if m)
    return body.get("message") or body.get("error") or f"HTTP {response.status_code}"
