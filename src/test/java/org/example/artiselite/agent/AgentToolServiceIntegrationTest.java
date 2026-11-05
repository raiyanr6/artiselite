package org.example.artiselite.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.artiselite.agent.AgentToolService.Direction;
import org.example.artiselite.agent.dto.ToolResults.*;
import org.example.artiselite.common.BusinessTime;
import org.example.artiselite.entity.Outbound;
import org.example.artiselite.entity.Product;
import org.example.artiselite.repository.OutboundRepository;
import org.example.artiselite.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises the four agent tools directly (no LLM involved) against the H2 test database,
 * as different roles, to check the numbers, the filters and the cost-field stripping.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentToolServiceIntegrationTest {

    @Autowired
    private AgentToolService tools;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private OutboundRepository outboundRepository;
    @Autowired
    private BusinessTime businessTime;
    @Autowired
    private ObjectMapper objectMapper;

    private Product jacket;

    @BeforeEach
    void seed() {
        jacket = product("FASH-1", "Denim Jacket", "Fashion Items", 2);      // short by 8
        product("FASH-2", "Silk Scarf", "Fashion Items", 9);                 // short by 1
        product("FASH-3", "Leather Belt", "Fashion Items", 50);              // not low
        Product archived = product("FASH-4", "Old Hat", "Fashion Items", 0);
        archived.setIsArchived(true);
        Product deleted = product("FASH-5", "Gone Shoe", "Fashion Items", 0);
        deleted.setIsDeleted(true);
        Product cable = product("ELEC-1", "USB Cable", "Electronics", 1);    // low, other category

        LocalDate today = businessTime.today();
        outbound(jacket, 3, today, "Acme Sdn Bhd");
        outbound(jacket, 4, today.minusDays(6), "Beta Trading");             // first day of the 7-day window
        outbound(jacket, 100, today.minusDays(7), "Too Old Ltd");            // just outside it
        outbound(cable, 7, today, "Acme Sdn Bhd");                           // other SKU
    }

    @Test
    @WithMockUser(authorities = "OPERATOR")
    void lowStock_inCategory_returnsOnlyActiveLowItems_worstFirst() {
        ProductListResult result = tools.getLowStockItems("fashion items", null);

        assertThat(result.items()).extracting(ProductSummary::sku).containsExactly("FASH-1", "FASH-2");
        assertThat(result.totalMatches()).isEqualTo(2);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    @WithMockUser(authorities = "OPERATOR")
    void operator_neverReceivesCostKeys() throws Exception {
        String json = objectMapper.writeValueAsString(tools.searchProducts("denim", null, null));

        assertThat(json).contains("FASH-1").doesNotContain("averageCost").doesNotContain("totalValue");
    }

    @Test
    @WithMockUser(authorities = "MANAGER")
    void manager_receivesCostFields() {
        ProductSummary item = tools.searchProducts("FASH-1", null, null).items().get(0);

        assertThat(item.averageCost()).isEqualByComparingTo("5");
        assertThat(item.totalValue()).isEqualByComparingTo("10"); // 2 units x 5
    }

    @Test
    @WithMockUser(authorities = "OPERATOR")
    void unknownCategory_returnsTheRealCategoriesAsAHint() {
        ProductListResult result = tools.getLowStockItems("Toys", null);

        assertThat(result.items()).isEmpty();
        assertThat(result.availableCategories()).contains("Fashion Items", "Electronics");
        assertThat(result.note()).contains("NOT an empty result");
    }

    @Test
    @WithMockUser(authorities = "OPERATOR")
    void partialCategoryName_withOneObviousMatch_isResolved_andSaysSo() {
        ProductListResult result = tools.getLowStockItems("fashion", null);

        assertThat(result.items()).extracting(ProductSummary::sku).containsExactly("FASH-1", "FASH-2");
        assertThat(result.note()).contains("Used 'Fashion Items'");
        assertThat(result.availableCategories()).isNull();
    }

    @Test
    @WithMockUser(authorities = "OPERATOR")
    void search_respectsLimit_andReportsTruncation() {
        ProductListResult result = tools.searchProducts("", "Fashion Items", 1);

        assertThat(result.items()).hasSize(1);
        assertThat(result.totalMatches()).isEqualTo(3); // FASH-1..3; archived and deleted excluded
        assertThat(result.truncated()).isTrue();
    }

    @Test
    @WithMockUser(authorities = "OPERATOR")
    void outboundLast7Days_forSku_countsOnlyTheWindow_withoutCustomersOrCost() throws Exception {
        TransactionHistoryResult result = tools.getTransactionHistory(Direction.OUT, "fash-1", null, 7, false);

        assertThat(result.from()).isEqualTo(businessTime.today().minusDays(6));
        assertThat(result.to()).isEqualTo(businessTime.today());
        assertThat(result.transactionCount()).isEqualTo(2);
        assertThat(result.totalUnits()).isEqualTo(7);
        assertThat(result.note()).isNull();
        String json = objectMapper.writeValueAsString(result);
        assertThat(json).doesNotContain("Acme").doesNotContain("totalValue").doesNotContain("\"value\"");
    }

    @Test
    @WithMockUser(authorities = "OPERATOR")
    void customerNames_appearOnlyWhenRequested() {
        TransactionHistoryResult result = tools.getTransactionHistory(Direction.OUT, "FASH-1", null, 7, true);

        assertThat(result.recent()).extracting(MovementRow::customerName)
                .containsExactly("Acme Sdn Bhd", "Beta Trading"); // newest first
    }

    @Test
    @WithMockUser(authorities = "MANAGER")
    void manager_seesMovementValue() {
        TransactionHistoryResult result = tools.getTransactionHistory(Direction.OUT, "FASH-1", null, 7, false);

        assertThat(result.totalValue()).isEqualByComparingTo("35"); // (3 + 4) x 5
    }

    @Test
    @WithMockUser(authorities = "OPERATOR")
    void unknownSku_returnsZeroWithAnExplicitNote() {
        TransactionHistoryResult result = tools.getTransactionHistory(Direction.OUT, "SKU#1", null, 7, false);

        assertThat(result.totalUnits()).isZero();
        assertThat(result.note()).contains("No product has SKU 'SKU#1'");
    }

    @Test
    @WithMockUser(authorities = "OPERATOR")
    void dashboard_forOperator_hasNoInventoryValue_andUsesBusinessToday() {
        DashboardSummary result = tools.getDashboardStats();

        assertThat(result.asOf()).isEqualTo(businessTime.today());
        assertThat(result.timezone()).isEqualTo("Asia/Kuala_Lumpur");
        assertThat(result.outboundToday()).isEqualTo(2);
        assertThat(result.totalInventoryValue()).isNull();
    }

    @Test
    void withoutAnAuthenticatedUser_toolsRefuseToRun() {
        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> tools.getLowStockItems(null, null));
    }

    private Product product(String sku, String name, String category, int quantity) {
        Product p = Product.builder()
                .sku(sku).name(name).category(category)
                .quantity(quantity).lowStockThreshold(10)
                .unitPrice(BigDecimal.TEN).averageCost(BigDecimal.valueOf(5))
                .isArchived(false).tags(new HashSet<>())
                .build();
        return productRepository.save(p);
    }

    private void outbound(Product product, int quantity, LocalDate date, String customer) {
        outboundRepository.save(Outbound.builder()
                .product(product).quantity(quantity).dispatchDate(date)
                .customerName(customer).salesOrderReference("SO-" + quantity)
                .unitValue(BigDecimal.valueOf(5)).totalValue(BigDecimal.valueOf(5L * quantity))
                .build());
    }
}
