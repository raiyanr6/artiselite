package org.example.artiselite.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * What the agent tools return. These are serialized to JSON and sent to the LLM, so:
 *  - keep them small (only fields a question could need, no free-text notes/descriptions)
 *  - null fields are omitted entirely (NON_NULL), so for an Operator the cost keys do not
 *    exist at all, rather than appearing as "averageCost": null.
 */
public final class ToolResults {

    private ToolResults() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductSummary(
            String sku,
            String name,
            String category,
            Integer quantity,
            Integer lowStockThreshold,
            boolean lowStock,
            BigDecimal averageCost,   // cost data: Admin/Manager only
            BigDecimal totalValue) {  // cost data: Admin/Manager only
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductListResult(
            String note,              // first, so the model reads it before the (possibly empty) items
            List<ProductSummary> items,
            long totalMatches,
            boolean truncated,
            List<String> availableCategories) { // only set when the requested category doesn't exist
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DashboardSummary(
            LocalDate asOf,
            String timezone,
            long totalActiveProducts,
            long inboundToday,
            long outboundToday,
            long lowStockCount,
            BigDecimal totalInventoryValue,              // cost data: Admin/Manager only
            Map<String, Long> transactionsPerDayLast7Days) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MovementRow(
            LocalDate date,
            String sku,
            String productName,
            int quantity,
            String reference,      // invoice reference (IN) or sales order reference (OUT)
            String customerName,   // OUT only, and only when explicitly requested
            BigDecimal value) {    // cost data: Admin/Manager only
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TransactionHistoryResult(
            String direction,
            LocalDate from,
            LocalDate to,
            String sku,
            String category,
            long transactionCount,
            long totalUnits,
            BigDecimal totalValue,   // cost data: Admin/Manager only
            List<MovementRow> recent,
            boolean truncated,
            String note,
            List<String> availableCategories) {
    }
}
