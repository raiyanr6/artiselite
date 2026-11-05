"""Tool tests against a fake Spring (httpx.MockTransport): no server, no OpenAI, no database."""
import json

import httpx
import pytest

from app.spring_client import SpringClient
from app.tools import execute_write, prepare_write, run_read_tool, tool_schemas

JACKET = {"id": 42, "sku": "FASH-1", "name": "Denim Jacket", "quantity": 5}
# The real search also matches names/categories, so return a near-miss too
SEARCH_RESULTS = [{"id": 7, "sku": "FASH-10", "name": "Wool Hat", "quantity": 9}, JACKET]


class FakeSpring:
    """Records every request and answers like Spring would."""

    def __init__(self, post_status: int = 200, post_body: dict | None = None):
        self.requests: list[httpx.Request] = []
        self.post_status = post_status
        self.post_body = post_body or {"id": 1}

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        if request.method == "POST":
            return httpx.Response(self.post_status, json=self.post_body)
        if request.url.path.endswith("/products/search"):
            keyword = request.url.params["keyword"].lower()
            return httpx.Response(200, json=[p for p in SEARCH_RESULTS if keyword in p["sku"].lower()])
        if request.url.path.endswith("/agent/tools/low-stock"):
            return httpx.Response(200, json={"items": [JACKET], "totalMatches": 1})
        return httpx.Response(404, json={"message": "Not found"})

    def posts(self) -> list[httpx.Request]:
        return [r for r in self.requests if r.method == "POST"]


@pytest.fixture
def spring():
    return FakeSpring()


def client_for(spring: FakeSpring) -> SpringClient:
    return SpringClient("user-jwt", transport=httpx.MockTransport(spring))


def test_every_tool_has_a_schema_for_openai():
    names = {s["function"]["name"] for s in tool_schemas()}
    assert names == {"search_products", "get_low_stock_items", "get_dashboard_stats", "get_transaction_history",
                     "create_product", "create_inbound", "create_outbound"}


def test_read_tool_forwards_the_users_token_and_returns_spring_json(spring):
    text = run_read_tool("get_low_stock_items", {"category": "Cloth"}, client_for(spring))

    assert json.loads(text)["items"][0]["sku"] == "FASH-1"
    request = spring.requests[0]
    assert request.headers["Authorization"] == "Bearer user-jwt"
    assert request.url.params["category"] == "Cloth"
    assert "limit" not in request.url.params  # empty arguments are left out


def test_prepare_inbound_resolves_the_exact_sku_and_sends_nothing(spring):
    action = prepare_write("create_inbound", {"sku": "fash-1", "quantity": 20, "unit_cost": 5.5}, client_for(spring))

    assert action["path"] == "/inbound"
    assert action["body"] == {"productId": 42, "quantity": 20, "unitCost": 5.5}  # not FASH-10's id
    assert action["summary"]["productName"] == "Denim Jacket"
    assert "productId" not in action["summary"]
    assert spring.posts() == []  # preparing never changes anything


def test_prepare_with_unknown_sku_returns_an_error_instead_of_an_action(spring):
    result = prepare_write("create_inbound", {"sku": "NOPE-1", "quantity": 1}, client_for(spring))

    assert "No active product has SKU 'NOPE-1'" in json.loads(result)["error"]


def test_invalid_arguments_are_caught_before_asking_for_approval(spring):
    result = prepare_write("create_inbound", {"sku": "FASH-1", "quantity": 0}, client_for(spring))

    assert "Invalid arguments" in json.loads(result)["error"]
    assert spring.requests == []


def test_outbound_more_than_in_stock_is_refused_before_approval(spring):
    result = prepare_write("create_outbound", {"sku": "FASH-1", "quantity": 6}, client_for(spring))

    assert "Only 5 of FASH-1 in stock" in json.loads(result)["error"]


def test_create_product_with_an_existing_sku_is_refused(spring):
    result = prepare_write("create_product", {"sku": "FASH-1", "name": "Dup", "category": "Cloth"}, client_for(spring))

    assert "already exists" in json.loads(result)["error"]


def test_execute_sends_exactly_the_prepared_body(spring):
    client = client_for(spring)
    action = prepare_write("create_outbound", {"sku": "FASH-1", "quantity": 2, "customer_name": "Acme"}, client)

    text = execute_write(action, client)

    assert json.loads(text)["status"] == "saved"
    post = spring.posts()[0]
    assert post.url.path.endswith("/outbound")
    assert json.loads(post.content) == {"productId": 42, "quantity": 2, "customerName": "Acme"}


def test_execute_explains_a_permission_refusal():
    spring = FakeSpring(post_status=403, post_body={"message": "Access Denied"})
    action = {"tool": "create_product", "path": "/products", "body": {"sku": "X"}, "summary": {}}

    text = execute_write(action, client_for(spring))

    assert "not allowed" in text and "Nothing was saved" in text


def test_execute_passes_springs_conflict_message_to_the_model():
    spring = FakeSpring(post_status=409, post_body={"message": "Insufficient stock. Available: 1"})
    action = {"tool": "create_outbound", "path": "/outbound", "body": {"productId": 42, "quantity": 2}, "summary": {}}

    text = execute_write(action, client_for(spring))

    assert "Insufficient stock. Available: 1" in text


def test_spring_being_down_is_an_error_text_not_a_crash():
    def down(request):
        raise httpx.ConnectError("refused")

    text = run_read_tool("get_dashboard_stats", {}, SpringClient("t", transport=httpx.MockTransport(down)))

    assert "Could not reach the warehouse API" in json.loads(text)["error"]
