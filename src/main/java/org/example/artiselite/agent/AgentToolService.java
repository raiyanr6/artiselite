package org.example.artiselite.agent;

import org.example.artiselite.agent.dto.ToolResults.*;
import org.example.artiselite.common.BusinessTime;
import org.example.artiselite.common.BusinessTime.DateRange;
import org.example.artiselite.dto.dashboard.DashboardResponse;
import org.example.artiselite.entity.Product;
import org.example.artiselite.repository.*;
import org.example.artiselite.security.CostVisibility;
import org.example.artiselite.service.DashboardService;
import org.example.artiselite.service.ProductService;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * The four read-only operations the chat agent may perform. Each one:
 *  - runs as the calling user, with the same @PreAuthorize rule as the matching REST endpoint
 *  - returns a small, capped, role-shaped result (cost fields only for Admin/Manager)
 *  - never writes anything (class-level readOnly transaction)
 *
 * Plain Java methods: the LLM wiring that exposes them as "tools" comes in a later step.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentToolService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;
    public static final int DEFAULT_DAYS = 7;
    public static final int RECENT_ROWS = 10;

    public enum Direction { IN, OUT }

    private final ProductRepository productRepository;
    private final InboundRepository inboundRepository;
    private final OutboundRepository outboundRepository;
    private final ProductService productService;
    private final DashboardService dashboardService;
    private final CostVisibility costVisibility;
    private final BusinessTime businessTime;

    @PreAuthorize("hasAnyAuthority('INVENTORY_READ', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public ProductListResult searchProducts(String query, String category, Integer limit) {
        CategoryMatch cat = matchCategory(category);
        Page<Product> page = productRepository.searchActive(normalize(query), cat.name(),
                PageRequest.of(0, clampLimit(limit), Sort.by("sku")));
        return toListResult(page, cat);
    }

    @PreAuthorize("hasAnyAuthority('INVENTORY_READ', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public ProductListResult getLowStockItems(String category, Integer limit) {
        CategoryMatch cat = matchCategory(category);
        Page<Product> page = productRepository.findLowStock(cat.name(), PageRequest.of(0, clampLimit(limit)));
        return toListResult(page, cat);
    }

    @PreAuthorize("hasAnyAuthority('DASHBOARD_VIEW', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public DashboardSummary getDashboardStats() {
        // Reuse the dashboard's own numbers (already cost-filtered) so chat and screen agree.
        // The recent-activity feed is dropped: it holds free text and people's names.
        DashboardResponse stats = dashboardService.getDashboardStats();
        return new DashboardSummary(
                businessTime.today(),
                businessTime.zone().getId(),
                stats.getTotalProducts(),
                stats.getTotalInboundToday(),
                stats.getTotalOutboundToday(),
                stats.getLowStockCount(),
                stats.getTotalInventoryValue(),
                new TreeMap<>(stats.getTransactionVolumeChart()));
    }

    // Same rule as GET /api/inbound or GET /api/outbound, depending on direction
    @PreAuthorize("#direction.name() == 'IN' " +
            "? hasAnyAuthority('INBOUND_READ', 'ADMIN', 'MANAGER', 'OPERATOR') " +
            ": hasAnyAuthority('OUTBOUND_READ', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public TransactionHistoryResult getTransactionHistory(Direction direction, String sku, String category,
                                                         Integer days, boolean includeCustomerNames) {
        DateRange range = businessTime.lastDays(days != null ? days : DEFAULT_DAYS);
        String skuFilter = normalize(sku);
        CategoryMatch catMatch = matchCategory(category);
        String cat = catMatch.name();
        boolean showCost = costVisibility.canViewCostData();
        Pageable recentPage = PageRequest.of(0, RECENT_ROWS);

        MovementTotals totals;
        List<MovementRow> recent;
        if (direction == Direction.IN) {
            totals = inboundRepository.sumMovements(range.from(), range.to(), skuFilter, cat);
            recent = inboundRepository.findMovements(range.from(), range.to(), skuFilter, cat, recentPage).stream()
                    .map(i -> new MovementRow(i.getReceivedDate(), i.getProduct().getSku(), i.getProduct().getName(),
                            i.getQuantity(), i.getInvoiceReference(), null,
                            showCost && i.getUnitCost() != null
                                    ? i.getUnitCost().multiply(BigDecimal.valueOf(i.getQuantity())) : null))
                    .toList();
        } else {
            totals = outboundRepository.sumMovements(range.from(), range.to(), skuFilter, cat);
            recent = outboundRepository.findMovements(range.from(), range.to(), skuFilter, cat, recentPage).stream()
                    .map(o -> new MovementRow(o.getDispatchDate(), o.getProduct().getSku(), o.getProduct().getName(),
                            o.getQuantity(), o.getSalesOrderReference(),
                            includeCustomerNames ? o.getCustomerName() : null,
                            showCost ? o.getTotalValue() : null))
                    .toList();
        }

        long count = totals.getTransactionCount() != null ? totals.getTransactionCount() : 0;
        long units = totals.getTotalUnits() != null ? totals.getTotalUnits() : 0;
        // Case-insensitive, matching how the movement queries compare SKUs
        String note = !skuFilter.isEmpty() && !productRepository.existsBySkuIgnoreCase(skuFilter)
                ? "No product has SKU '" + skuFilter + "'. Use search_products to find the exact SKU."
                : null;
        if (catMatch.note() != null) {
            note = note == null ? catMatch.note() : note + " " + catMatch.note();
        }

        return new TransactionHistoryResult(
                direction.name(), range.from(), range.to(),
                skuFilter.isEmpty() ? null : skuFilter,
                cat.isEmpty() ? null : cat,
                count, units,
                showCost ? totals.getTotalValue() : null,
                recent, count > recent.size(), note, catMatch.availableCategories());
    }

    private ProductListResult toListResult(Page<Product> page, CategoryMatch category) {
        boolean showCost = costVisibility.canViewCostData();
        List<ProductSummary> items = page.getContent().stream()
                .map(p -> new ProductSummary(
                        p.getSku(), p.getName(), p.getCategory(), p.getQuantity(), p.getLowStockThreshold(),
                        p.getLowStockThreshold() != null && p.getQuantity() <= p.getLowStockThreshold(),
                        showCost ? p.getAverageCost() : null,
                        showCost ? productService.calculateProductValuation(p.getId()) : null))
                .toList();
        return new ProductListResult(category.note(), items, page.getTotalElements(),
                page.getTotalElements() > items.size(), category.availableCategories());
    }

    /**
     * The category to filter by, after fixing up the model's guess:
     *   ""                      -> no filter
     *   exact (any case)        -> used as is
     *   "fashion" and only "Fashion Items" contains it -> uses "Fashion Items", and the note says so
     *   anything else           -> nothing matches; the note and the real category list go back to the model
     * In a live test the model guessed "fashion", got an empty list plus the real names, and still told
     * the user "nothing is low". So the server resolves the obvious case itself and spells out the rest.
     */
    record CategoryMatch(String name, String note, List<String> availableCategories) {
    }

    private CategoryMatch matchCategory(String requested) {
        String cat = normalize(requested);
        if (cat.isEmpty()) {
            return new CategoryMatch("", null, null);
        }
        List<String> categories = productRepository.findActiveCategories();
        if (categories.stream().anyMatch(c -> c.equalsIgnoreCase(cat))) {
            return new CategoryMatch(cat, null, null);
        }
        String lower = cat.toLowerCase(Locale.ROOT);
        List<String> close = categories.stream().filter(c -> c.toLowerCase(Locale.ROOT).contains(lower)).toList();
        if (close.size() == 1) {
            return new CategoryMatch(close.get(0), "No category is named '" + cat + "'. Used '" + close.get(0)
                    + "', the only category containing that word. Mention this to the user.", null);
        }
        return new CategoryMatch(cat, "No category is named '" + cat + "', so nothing was searched. "
                + "This is NOT an empty result. Call the tool again with the right name from availableCategories, "
                + "or ask the user which category they mean.", categories);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }
}
