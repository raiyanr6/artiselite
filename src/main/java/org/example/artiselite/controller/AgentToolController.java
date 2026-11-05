package org.example.artiselite.controller;

import org.example.artiselite.agent.AgentToolService;
import org.example.artiselite.agent.AgentToolService.Direction;
import org.example.artiselite.agent.dto.ToolResults.DashboardSummary;
import org.example.artiselite.agent.dto.ToolResults.ProductListResult;
import org.example.artiselite.agent.dto.ToolResults.TransactionHistoryResult;
import org.example.artiselite.common.BusinessTime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The agent's read-only tools over HTTP, for the Python LangGraph service.
 * Python forwards the user's own JWT, so every call runs as that user: the @PreAuthorize
 * rules and cost hiding inside AgentToolService apply exactly as they do for the Java chatbot.
 */
@RestController
@RequestMapping("/api/agent/tools")
@RequiredArgsConstructor
public class AgentToolController {

    private final AgentToolService tools;

    // Lets the agent service learn who a token belongs to, verified by Spring (it owns the JWT secret).
    // Used to make sure a conversation is only ever continued by the user who started it.
    @GetMapping("/whoami")
    public Map<String, String> whoami(Authentication authentication) {
        return Map.of("username", authentication.getName());
    }

    @GetMapping("/products")
    public ProductListResult searchProducts(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @Min(1) @Max(AgentToolService.MAX_LIMIT) Integer limit) {
        return tools.searchProducts(query, category, limit);
    }

    @GetMapping("/low-stock")
    public ProductListResult lowStock(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @Min(1) @Max(AgentToolService.MAX_LIMIT) Integer limit) {
        return tools.getLowStockItems(category, limit);
    }

    @GetMapping("/dashboard")
    public DashboardSummary dashboard() {
        return tools.getDashboardStats();
    }

    @GetMapping("/transactions")
    public TransactionHistoryResult transactions(
            @RequestParam Direction direction,
            @RequestParam(required = false) String sku,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @Min(1) @Max(BusinessTime.MAX_WINDOW_DAYS) Integer days,
            @RequestParam(defaultValue = "false") boolean includeCustomerNames) {
        return tools.getTransactionHistory(direction, sku, category, days, includeCustomerNames);
    }
}
