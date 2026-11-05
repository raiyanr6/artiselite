"""The agent's tools.

Read tools run straight away. Write tools are split in two so the graph (Block 4) can pause in between:
    prepare_write()  -> check the arguments, look up the product, build the exact request (no change made)
    execute_write()  -> send that exact request to Spring (only after the user approves)
Every function returns text for the model and never raises, so one bad call can't crash a conversation.
"""
import json
from typing import Any, Literal, TypedDict

from pydantic import BaseModel, Field, ValidationError

from app.spring_client import SpringClient, SpringError

MAX_RESULT_CHARS = 12_000  # same cap as the Java chatbot: a huge result would flood the model


# ---------- Arguments the model may send (also become the tool schemas OpenAI sees) ----------

class SearchProductsArgs(BaseModel):
    """Find active products by name or SKU, optionally within one category."""
    query: str | None = Field(None, description="Words from the product name or SKU")
    category: str | None = Field(None, description="Exact category name")
    limit: int | None = Field(None, ge=1, le=50)


class LowStockArgs(BaseModel):
    """List products at or below their low-stock threshold, worst first."""
    category: str | None = Field(None, description="Exact category name")
    limit: int | None = Field(None, ge=1, le=50)


class DashboardArgs(BaseModel):
    """Today's totals: products, inbound/outbound today, low-stock count, last 7 days of activity."""


class TransactionHistoryArgs(BaseModel):
    """Stock received (IN) or dispatched (OUT) over the last N days, with totals and recent rows."""
    direction: Literal["IN", "OUT"]
    sku: str | None = None
    category: str | None = None
    days: int | None = Field(None, ge=1, le=90, description="Last N days including today (default 7)")
    include_customer_names: bool = Field(False, description="Only when the user asks for customer names")


class CreateProductArgs(BaseModel):
    """Add a new product. Needs the user's approval before it is saved."""
    sku: str = Field(min_length=1, max_length=64)
    name: str = Field(min_length=1, max_length=200)
    category: str = Field(min_length=1, max_length=100, description="Prefer an existing category name")
    quantity: int = Field(0, ge=0, description="Starting stock")
    low_stock_threshold: int | None = Field(None, ge=0)
    unit_price: float | None = Field(None, ge=0)
    average_cost: float | None = Field(None, ge=0)
    description: str | None = Field(None, max_length=1000)


class CreateInboundArgs(BaseModel):
    """Record stock received for an existing product. Needs the user's approval before it is saved."""
    sku: str = Field(min_length=1)
    quantity: int = Field(ge=1)
    unit_cost: float | None = Field(None, ge=0)
    invoice_reference: str | None = Field(None, max_length=100)
    batch_number: str | None = Field(None, max_length=100)
    notes: str | None = Field(None, max_length=500)


class CreateOutboundArgs(BaseModel):
    """Record stock dispatched for an existing product. Needs the user's approval before it is saved."""
    sku: str = Field(min_length=1)
    quantity: int = Field(ge=1)
    customer_name: str | None = Field(None, max_length=200)
    sales_order_reference: str | None = Field(None, max_length=100)
    notes: str | None = Field(None, max_length=500)


READ_TOOLS: dict[str, type[BaseModel]] = {
    "search_products": SearchProductsArgs,
    "get_low_stock_items": LowStockArgs,
    "get_dashboard_stats": DashboardArgs,
    "get_transaction_history": TransactionHistoryArgs,
}
WRITE_TOOLS: dict[str, type[BaseModel]] = {
    "create_product": CreateProductArgs,
    "create_inbound": CreateInboundArgs,
    "create_outbound": CreateOutboundArgs,
}


def tool_schemas() -> list[dict]:
    """OpenAI-format tool definitions, built from the argument classes above."""
    schemas = []
    for name, model in {**READ_TOOLS, **WRITE_TOOLS}.items():
        parameters = model.model_json_schema()
        parameters.pop("title", None)
        parameters.pop("description", None)
        schemas.append({"type": "function", "function": {
            "name": name, "description": model.__doc__, "parameters": parameters}})
    return schemas


# ---------- Read tools ----------

def run_read_tool(name: str, raw_args: dict, client: SpringClient) -> str:
    try:
        args = READ_TOOLS[name].model_validate(raw_args or {})
    except KeyError:
        return _error(f"Unknown tool '{name}'.")
    except ValidationError as e:
        return _invalid(e)

    try:
        if name == "search_products":
            result = client.get("/agent/tools/products", {"query": args.query, "category": args.category, "limit": args.limit})
        elif name == "get_low_stock_items":
            result = client.get("/agent/tools/low-stock", {"category": args.category, "limit": args.limit})
        elif name == "get_dashboard_stats":
            result = client.get("/agent/tools/dashboard")
        else:
            result = client.get("/agent/tools/transactions", {
                "direction": args.direction, "sku": args.sku, "category": args.category,
                "days": args.days, "includeCustomerNames": str(args.include_customer_names).lower()})
    except SpringError as e:
        return _spring_error(e)
    return _to_text(result)


# ---------- Write tools ----------

class PreparedAction(TypedDict):
    """Everything about a write, decided before asking the user. Plain JSON so it can be saved while paused."""
    tool: str
    path: str               # Spring endpoint, e.g. /inbound
    body: dict[str, Any]    # the exact JSON that will be sent on approval
    summary: dict[str, Any]  # what the approval card shows (taken from this request, not from the model's prose)


def prepare_write(name: str, raw_args: dict, client: SpringClient) -> PreparedAction | str:
    """Returns the action to approve, or an error text for the model (in which case nothing is shown to approve)."""
    try:
        args = WRITE_TOOLS[name].model_validate(raw_args or {})
    except KeyError:
        return _error(f"Unknown tool '{name}'.")
    except ValidationError as e:
        return _invalid(e)

    try:
        if name == "create_product":
            return _prepare_product(args, client)
        if name == "create_inbound":
            return _prepare_inbound(args, client)
        return _prepare_outbound(args, client)
    except SpringError as e:
        return _spring_error(e)


def execute_write(action: PreparedAction, client: SpringClient) -> str:
    """Sends the approved request unchanged. Spring re-checks permissions and stock, so this can still fail."""
    try:
        result = client.post(action["path"], action["body"])
    except SpringError as e:
        return _spring_error(e) + " Nothing was saved."
    return _to_text({"status": "saved", "result": result})


def _prepare_product(args: CreateProductArgs, client: SpringClient) -> PreparedAction | str:
    sku = args.sku.strip()
    if _find_by_sku(sku, client) is not None:
        return _error(f"A product with SKU '{sku}' already exists. Nothing was created.")
    body = _drop_none({
        "sku": sku, "name": args.name.strip(), "category": args.category.strip(), "quantity": args.quantity,
        "lowStockThreshold": args.low_stock_threshold, "unitPrice": args.unit_price,
        "averageCost": args.average_cost, "description": args.description,
    })
    return PreparedAction(tool="create_product", path="/products", body=body, summary=dict(body))


def _prepare_inbound(args: CreateInboundArgs, client: SpringClient) -> PreparedAction | str:
    product = _find_by_sku(args.sku.strip(), client)
    if product is None:
        return _no_such_sku(args.sku)
    body = _drop_none({
        "productId": product["id"], "quantity": args.quantity, "unitCost": args.unit_cost,
        "invoiceReference": args.invoice_reference, "batchNumber": args.batch_number, "notes": args.notes,
    })
    return PreparedAction(tool="create_inbound", path="/inbound", body=body,
                          summary=_movement_summary(product, body))


def _prepare_outbound(args: CreateOutboundArgs, client: SpringClient) -> PreparedAction | str:
    product = _find_by_sku(args.sku.strip(), client)
    if product is None:
        return _no_such_sku(args.sku)
    in_stock = product.get("quantity") or 0
    if args.quantity > in_stock:
        # Spring would refuse this too (409); stopping here avoids asking the user to approve a sure failure
        return _error(f"Only {in_stock} of {product['sku']} in stock, so {args.quantity} cannot be dispatched. "
                      "Nothing was created.")
    body = _drop_none({
        "productId": product["id"], "quantity": args.quantity, "customerName": args.customer_name,
        "salesOrderReference": args.sales_order_reference, "notes": args.notes,
    })
    return PreparedAction(tool="create_outbound", path="/outbound", body=body,
                          summary=_movement_summary(product, body))


def _find_by_sku(sku: str, client: SpringClient) -> dict | None:
    """The search endpoint also matches names and categories, so keep only the exact SKU (any case)."""
    matches = client.get("/products/search", {"keyword": sku}) or []
    return next((p for p in matches if str(p.get("sku", "")).lower() == sku.lower()), None)


def _movement_summary(product: dict, body: dict) -> dict:
    # Show SKU and name instead of the internal productId; everything else is the request as sent
    summary = {"sku": product["sku"], "productName": product.get("name"), "currentStock": product.get("quantity")}
    summary.update({k: v for k, v in body.items() if k != "productId"})
    return summary


# ---------- Text for the model ----------

def _spring_error(e: SpringError) -> str:
    if e.status == 401:
        return _error("The user's session has expired. Ask them to log in again.")
    if e.status == 403:
        return _error("The user's role is not allowed to do this. Explain that to the user; do not retry.")
    if e.status == 0:
        return _error(e.message)
    return _error(f"The warehouse API refused the request ({e.status}): {e.message}")


def _invalid(e: ValidationError) -> str:
    problems = "; ".join(f"{'.'.join(str(p) for p in err['loc']) or 'arguments'}: {err['msg']}" for err in e.errors())
    return _error(f"Invalid arguments: {problems}. Fix them or ask the user.")


def _no_such_sku(sku: str) -> str:
    return _error(f"No active product has SKU '{sku.strip()}'. Use search_products to find the exact SKU, "
                  "or ask the user. Nothing was created.")


def _error(message: str) -> str:
    return json.dumps({"error": message})


def _to_text(result: Any) -> str:
    text = json.dumps(result, default=str)
    if len(text) > MAX_RESULT_CHARS:
        return text[:MAX_RESULT_CHARS] + '..."(truncated: ask a narrower question)"'
    return text


def _drop_none(d: dict) -> dict:
    return {k: v for k, v in d.items() if v is not None}
