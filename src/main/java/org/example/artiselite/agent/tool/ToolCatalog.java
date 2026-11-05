package org.example.artiselite.agent.tool;

import org.example.artiselite.agent.AgentToolService;
import org.example.artiselite.agent.AgentToolService.Direction;
import org.example.artiselite.common.BusinessTime;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The fixed set of tools the agent can use: for each one, the definition sent to the model
 * and the Java handler that runs it. Adding a tool means adding an entry here; the model
 * cannot reach anything that isn't listed.
 */
@Component
public class ToolCatalog {

    public static final String SEARCH_PRODUCTS = "search_products";
    public static final String GET_LOW_STOCK_ITEMS = "get_low_stock_items";
    public static final String GET_DASHBOARD_STATS = "get_dashboard_stats";
    public static final String GET_TRANSACTION_HISTORY = "get_transaction_history";

    public record RegisteredTool(ToolDefinition definition, Function<ToolArgs, Object> handler) {
    }

    private final Map<String, RegisteredTool> tools = new LinkedHashMap<>();

    public ToolCatalog(AgentToolService service) {
        register(new ToolDefinition(SEARCH_PRODUCTS,
                "Search active products by keyword (matches name, SKU or category) and/or exact category. "
                        + "Returns stock quantity and low-stock threshold per product. Use it to check stock levels "
                        + "or to find the exact SKU before calling get_transaction_history.",
                objectSchema(Map.of(
                        "query", stringProp("Keyword to match against product name, SKU or category."),
                        "category", stringProp("Exact category name, case-insensitive."),
                        "limit", limitProp()))),
                args -> service.searchProducts(
                        args.optionalString("query"),
                        args.optionalString("category"),
                        args.optionalInt("limit", 1, AgentToolService.MAX_LIMIT)));

        register(new ToolDefinition(GET_LOW_STOCK_ITEMS,
                "List active products whose quantity is at or below their low-stock threshold, "
                        + "largest shortfall first. Optionally limited to one category.",
                objectSchema(Map.of(
                        "category", stringProp("Exact category name, case-insensitive."),
                        "limit", limitProp()))),
                args -> service.getLowStockItems(
                        args.optionalString("category"),
                        args.optionalInt("limit", 1, AgentToolService.MAX_LIMIT)));

        register(new ToolDefinition(GET_DASHBOARD_STATS,
                "Today's warehouse summary in the business timezone: active product count, today's inbound "
                        + "and outbound transaction counts, low-stock count, and transactions per day for the last 7 days.",
                objectSchema(Map.of())),
                args -> service.getDashboardStats());

        register(new ToolDefinition(GET_TRANSACTION_HISTORY,
                "Stock movements over the last N days: totals (transaction count, units) computed by the server, "
                        + "plus the most recent rows. Use the returned totals; do not add up rows yourself.",
                objectSchema(Map.of(
                        "direction", Map.of(
                                "type", "string",
                                "enum", List.of("IN", "OUT"),
                                "description", "IN = stock received (inbound). OUT = stock dispatched (outbound)."),
                        "sku", stringProp("Exact SKU, case-insensitive. Omit to cover all products."),
                        "category", stringProp("Exact category name, case-insensitive."),
                        "days", Map.of(
                                "type", "integer",
                                "minimum", 1,
                                "maximum", BusinessTime.MAX_WINDOW_DAYS,
                                "description", "Window of N calendar days ending today. 'Last week' = 7. Default 7."),
                        "include_customer_names", Map.of(
                                "type", "boolean",
                                "description", "Only set true if the user explicitly asks about customers. Default false.")),
                        List.of("direction"))),
                args -> service.getTransactionHistory(
                        args.requiredEnum("direction", Direction.class),
                        args.optionalString("sku"),
                        args.optionalString("category"),
                        args.optionalInt("days", 1, BusinessTime.MAX_WINDOW_DAYS),
                        args.optionalBoolean("include_customer_names", false)));
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(RegisteredTool::definition).toList();
    }

    public Optional<RegisteredTool> find(String name) {
        return Optional.ofNullable(name).map(tools::get);
    }

    private void register(ToolDefinition definition, Function<ToolArgs, Object> handler) {
        tools.put(definition.name(), new RegisteredTool(definition, handler));
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties) {
        return objectSchema(properties, List.of());
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", required,
                "additionalProperties", false);
    }

    private static Map<String, Object> stringProp(String description) {
        return Map.of("type", "string", "maxLength", ToolArgs.MAX_STRING_LENGTH, "description", description);
    }

    private static Map<String, Object> limitProp() {
        return Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", AgentToolService.MAX_LIMIT,
                "description", "Maximum number of products to return. Default " + AgentToolService.DEFAULT_LIMIT + ".");
    }
}
