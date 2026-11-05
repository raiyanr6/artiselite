"""The whole agent through its HTTP endpoints: FastAPI -> LangGraph -> tools -> fake Spring.
The model is scripted (no OpenAI), so every test is fast, free and repeatable."""
import json

import httpx
import pytest
from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage, ToolMessage
from langgraph.checkpoint.memory import InMemorySaver

import app.main
from app.config import settings
from app.graph import LIMIT_REPLY, build_graph
from app.spring_client import SpringClient

JACKET = {"id": 42, "sku": "FASH-1", "name": "Denim Jacket", "quantity": 5}
USERS = {"alice-jwt": "alice", "bob-jwt": "bob"}


class FakeSpring:
    def __init__(self):
        self.requests: list[httpx.Request] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        token = request.headers["Authorization"].removeprefix("Bearer ")
        if token not in USERS:
            return httpx.Response(401, json={"message": "Unauthorized"})
        path = request.url.path
        if path.endswith("/whoami"):
            return httpx.Response(200, json={"username": USERS[token]})
        if path.endswith("/products/search"):
            return httpx.Response(200, json=[JACKET] if "fash-1" in request.url.params["keyword"].lower() else [])
        if path.endswith("/agent/tools/low-stock"):
            return httpx.Response(200, json={"items": [JACKET], "totalMatches": 1})
        if request.method == "POST" and path.endswith("/inbound"):
            return httpx.Response(200, json={"id": 9, "quantity": json.loads(request.content)["quantity"]})
        return httpx.Response(404, json={"message": "Not found"})

    def posts(self):
        return [r for r in self.requests if r.method == "POST"]


class ScriptedModel:
    """Stands in for ChatOpenAI: returns the next scripted reply and records what it was shown."""

    def __init__(self, *replies: AIMessage):
        self.replies = list(replies)
        self.seen: list[list] = []

    def bind_tools(self, tools, **kwargs):
        self.bound_kwargs = kwargs
        return self

    def invoke(self, messages):
        self.seen.append(messages)
        return self.replies.pop(0)


def tool_call(name: str, args: dict, call_id: str = "call_1") -> AIMessage:
    return AIMessage("", tool_calls=[{"name": name, "args": args, "id": call_id}])


@pytest.fixture
def spring(monkeypatch):
    fake = FakeSpring()
    real = SpringClient
    factory = lambda token: real(token, transport=httpx.MockTransport(fake))  # noqa: E731
    monkeypatch.setattr(app.main, "SpringClient", factory)
    return fake


@pytest.fixture
def checkpointer():
    return InMemorySaver()


def agent_with(model: ScriptedModel, checkpointer) -> TestClient:
    app.main.set_graph(build_graph(model, checkpointer))
    return TestClient(app.main.app)


def chat(client, message, token="alice-jwt", thread_id=None):
    body = {"message": message} | ({"threadId": thread_id} if thread_id else {})
    return client.post("/chat", json=body, headers={"Authorization": f"Bearer {token}"})


def resume(client, thread_id, approved, token="alice-jwt"):
    return client.post("/chat/resume", json={"threadId": thread_id, "approved": approved},
                       headers={"Authorization": f"Bearer {token}"})


INBOUND_20 = tool_call("create_inbound", {"sku": "FASH-1", "quantity": 20})


def test_read_question_is_answered_with_tool_data(spring, checkpointer):
    model = ScriptedModel(tool_call("get_low_stock_items", {}), AIMessage("FASH-1 is low (5 left)."))
    client = agent_with(model, checkpointer)

    response = chat(client, "What is low on stock?")

    assert response.status_code == 200
    assert response.json()["reply"] == "FASH-1 is low (5 left)."
    tool_result = model.seen[1][-1]
    assert isinstance(tool_result, ToolMessage) and "Denim Jacket" in tool_result.content
    assert model.bound_kwargs["parallel_tool_calls"] is False


def test_write_pauses_for_approval_and_sends_nothing_yet(spring, checkpointer):
    client = agent_with(ScriptedModel(INBOUND_20), checkpointer)

    body = chat(client, "Receive 20 FASH-1").json()

    assert "reply" not in body
    assert body["pendingAction"] == {"tool": "create_inbound", "summary": {
        "sku": "FASH-1", "productName": "Denim Jacket", "currentStock": 5, "quantity": 20}}
    assert spring.posts() == []


def test_approve_sends_the_exact_request_once_and_the_agent_confirms(spring, checkpointer):
    model = ScriptedModel(INBOUND_20, AIMessage("Done. Received 20 x Denim Jacket."))
    client = agent_with(model, checkpointer)
    thread_id = chat(client, "Receive 20 FASH-1").json()["threadId"]

    body = resume(client, thread_id, approved=True).json()

    assert body["reply"] == "Done. Received 20 x Denim Jacket."
    assert len(spring.posts()) == 1
    assert json.loads(spring.posts()[0].content) == {"productId": 42, "quantity": 20}
    assert '"status": "saved"' in model.seen[-1][-1].content


def test_reject_sends_nothing_and_the_agent_is_told(spring, checkpointer):
    model = ScriptedModel(INBOUND_20, AIMessage("OK, nothing was saved."))
    client = agent_with(model, checkpointer)
    thread_id = chat(client, "Receive 20 FASH-1").json()["threadId"]

    body = resume(client, thread_id, approved=False).json()

    assert body["reply"] == "OK, nothing was saved."
    assert spring.posts() == []
    assert "declined" in model.seen[-1][-1].content


def test_second_approve_is_refused_and_nothing_is_sent_twice(spring, checkpointer):
    client = agent_with(ScriptedModel(INBOUND_20, AIMessage("Done.")), checkpointer)
    thread_id = chat(client, "Receive 20 FASH-1").json()["threadId"]
    resume(client, thread_id, approved=True)

    second = resume(client, thread_id, approved=True)

    assert second.status_code == 409
    assert len(spring.posts()) == 1


def test_new_message_while_a_change_is_waiting_is_refused(spring, checkpointer):
    client = agent_with(ScriptedModel(INBOUND_20), checkpointer)
    thread_id = chat(client, "Receive 20 FASH-1").json()["threadId"]

    response = chat(client, "Actually make it 30", thread_id=thread_id)

    assert response.status_code == 409


def test_another_user_cannot_continue_or_approve_my_conversation(spring, checkpointer):
    client = agent_with(ScriptedModel(INBOUND_20), checkpointer)
    thread_id = chat(client, "Receive 20 FASH-1").json()["threadId"]

    assert resume(client, thread_id, approved=True, token="bob-jwt").status_code == 404
    assert chat(client, "hi", token="bob-jwt", thread_id=thread_id).status_code == 404
    assert spring.posts() == []


def test_unknown_sku_never_reaches_approval(spring, checkpointer):
    model = ScriptedModel(tool_call("create_inbound", {"sku": "NOPE-9", "quantity": 1}),
                          AIMessage("I couldn't find SKU NOPE-9."))
    client = agent_with(model, checkpointer)

    body = chat(client, "Receive 1 NOPE-9").json()

    assert body["reply"] == "I couldn't find SKU NOPE-9."
    assert "pendingAction" not in body
    assert "No active product has SKU" in model.seen[-1][-1].content


def test_missing_or_expired_login_is_rejected(spring, checkpointer):
    client = agent_with(ScriptedModel(), checkpointer)

    assert client.post("/chat", json={"message": "hi"}).status_code == 401
    assert chat(client, "hi", token="expired-jwt").status_code == 401


def test_tool_rounds_are_capped_per_message(spring, checkpointer):
    loops = [tool_call("get_low_stock_items", {}, f"call_{i}") for i in range(settings.max_tool_rounds)]
    model = ScriptedModel(*loops)
    client = agent_with(model, checkpointer)

    body = chat(client, "Loop forever").json()

    assert body["reply"] == LIMIT_REPLY
    assert len(model.seen) == settings.max_tool_rounds  # the model wasn't asked again after the cap


def test_conversation_is_remembered_within_a_thread(spring, checkpointer):
    model = ScriptedModel(AIMessage("Hi Alice."), AIMessage("You said hello."))
    client = agent_with(model, checkpointer)
    thread_id = chat(client, "Hello").json()["threadId"]

    chat(client, "What did I say?", thread_id=thread_id)

    contents = [m.content for m in model.seen[-1]]
    assert "Hello" in contents and "Hi Alice." in contents


def test_the_users_token_is_never_saved_by_the_checkpointer(spring, checkpointer):
    client = agent_with(ScriptedModel(INBOUND_20), checkpointer)
    chat(client, "Receive 20 FASH-1")

    saved = "".join(repr(t) for t in checkpointer.list(None)) + repr(checkpointer.storage) + repr(checkpointer.writes)

    assert "alice-jwt" not in saved
