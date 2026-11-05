"""HTTP endpoints for the React chat panel.

    POST /chat          {message, threadId?}     -> {threadId, reply} or {threadId, pendingAction}
    POST /chat/resume   {threadId, approved}     -> same shape (the agent may answer, or propose another change)

Run from agent-service/:  .venv/Scripts/python -m uvicorn app.main:app --port 8000
"""
import logging
import threading
import uuid
from typing import Any

from fastapi import FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from langchain_core.messages import AIMessage, HumanMessage
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.errors import GraphRecursionError
from langgraph.types import Command
from pydantic import BaseModel, Field

from app.config import settings
from app.graph import LIMIT_REPLY, build_graph
from app.spring_client import SpringClient, SpringError

log = logging.getLogger("agent")

app = FastAPI(title="Artiselite agent")
app.add_middleware(
    CORSMiddleware,
    allow_origins=[settings.frontend_origin],  # only the React app's browser pages may call this service
    allow_methods=["POST"],
    allow_headers=["Authorization", "Content-Type"],
)

# Conversation memory lives in this process: a restart forgets chats and any waiting approvals
# (safe: nothing waiting has been executed).
_graph = None
_owners: dict[str, str] = {}   # threadId -> username who started it
_busy: set[str] = set()        # threads with a request in progress
_lock = threading.Lock()


def get_graph():
    global _graph
    if _graph is None:  # built on first use, so the app (and tests) can start without an OpenAI key
        from langchain_openai import ChatOpenAI
        if not settings.openai_api_key:
            raise HTTPException(503, "The assistant is not configured (OPENAI_API_KEY is missing).")
        llm = ChatOpenAI(model=settings.openai_model, api_key=settings.openai_api_key,
                         max_tokens=800, timeout=30, max_retries=1)
        _graph = build_graph(llm, InMemorySaver())
    return _graph


def set_graph(graph) -> None:
    """Tests use this to plug in a graph built with a fake model."""
    global _graph
    _graph = graph
    _owners.clear()
    _busy.clear()


# ---------- Request / response shapes ----------

class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=1000)
    threadId: uuid.UUID | None = None


class ResumeRequest(BaseModel):
    threadId: uuid.UUID
    approved: bool


class PendingAction(BaseModel):
    tool: str
    summary: dict[str, Any]


class ChatResponse(BaseModel):
    threadId: str
    reply: str | None = None
    pendingAction: PendingAction | None = None


# ---------- Endpoints ----------

@app.post("/chat", response_model=ChatResponse, response_model_exclude_none=True)
def chat(request: ChatRequest, authorization: str | None = Header(None)) -> ChatResponse:
    with SpringClient(_token(authorization)) as spring:
        username = _whoami(spring)
        graph = get_graph()

        if request.threadId is None:
            thread_id = str(uuid.uuid4())  # created here, never chosen by the browser
            with _lock:
                _owners[thread_id] = username
        else:
            thread_id = str(request.threadId)
            _check_owner(thread_id, username)

        with _claim(thread_id):
            config = _config(thread_id, spring)
            if _waiting_action(graph, config):
                raise HTTPException(409, "Approve or reject the pending change first.")
            new_turn = {"messages": [HumanMessage(request.message.strip())], "tool_rounds": 0, "pending_action": None}
            return _run(graph, new_turn, config, thread_id)


@app.post("/chat/resume", response_model=ChatResponse, response_model_exclude_none=True)
def resume(request: ResumeRequest, authorization: str | None = Header(None)) -> ChatResponse:
    with SpringClient(_token(authorization)) as spring:
        username = _whoami(spring)
        graph = get_graph()
        thread_id = str(request.threadId)
        _check_owner(thread_id, username)

        with _claim(thread_id):  # a double click gets 409 here instead of sending the change twice
            config = _config(thread_id, spring)
            if _waiting_action(graph, config) is None:
                raise HTTPException(409, "There is no change waiting for approval.")
            log.info("user=%s thread=%s decision=%s", username, thread_id,
                     "approved" if request.approved else "rejected")
            return _run(graph, Command(resume={"approved": request.approved}), config, thread_id)


# ---------- Helpers ----------

def _run(graph, graph_input, config: dict, thread_id: str) -> ChatResponse:
    try:
        graph.invoke(graph_input, config)
    except GraphRecursionError:  # safety net only: the agent node normally stops at max_tool_rounds first
        return ChatResponse(threadId=thread_id, reply=LIMIT_REPLY)
    except HTTPException:
        raise
    except Exception:  # OpenAI down, timeout, etc. Logged without message contents.
        log.exception("agent run failed thread=%s", thread_id)
        raise HTTPException(503, "The assistant is unavailable right now. Please try again in a moment.")

    waiting = _waiting_action(graph, config)
    if waiting is not None:
        return ChatResponse(threadId=thread_id, pendingAction=PendingAction(**waiting))
    last = graph.get_state(config).values["messages"][-1]
    reply = last.content if isinstance(last, AIMessage) and isinstance(last.content, str) else ""
    return ChatResponse(threadId=thread_id, reply=reply or "Sorry, I couldn't produce an answer.")


def _waiting_action(graph, config: dict) -> dict | None:
    """The approval card's data if this thread is paused at interrupt(), else None."""
    interrupts = graph.get_state(config).interrupts
    return interrupts[0].value if interrupts else None


def _config(thread_id: str, spring: SpringClient) -> dict:
    return {
        # The client object (not the raw token string) so the checkpointer never copies the token
        "configurable": {"thread_id": thread_id, "spring": spring},
        # ~4 graph steps per tool round, plus room for the final answer
        "recursion_limit": settings.max_tool_rounds * 4 + 6,
    }


def _token(authorization: str | None) -> str:
    if not authorization or not authorization.startswith("Bearer ") or not authorization[7:].strip():
        raise HTTPException(401, "Not logged in.")
    return authorization[7:].strip()


def _whoami(spring: SpringClient) -> str:
    """Spring checks the token's signature and expiry; Python never sees the JWT secret."""
    try:
        return spring.get("/agent/tools/whoami")["username"]
    except SpringError as e:
        if e.status in (401, 403):
            raise HTTPException(401, "Your session has expired. Please log in again.")
        raise HTTPException(503, "The warehouse API is unavailable right now.")


def _check_owner(thread_id: str, username: str) -> None:
    with _lock:
        owner = _owners.get(thread_id)
    if owner != username:  # unknown thread, or someone else's: same answer, so ids can't be probed
        raise HTTPException(404, "Conversation not found. Start a new one.")


class _claim:
    """Lets only one request at a time work on a thread."""

    def __init__(self, thread_id: str):
        self.thread_id = thread_id

    def __enter__(self):
        with _lock:
            if self.thread_id in _busy:
                raise HTTPException(409, "Still working on your previous message.")
            _busy.add(self.thread_id)

    def __exit__(self, *exc):
        with _lock:
            _busy.discard(self.thread_id)
