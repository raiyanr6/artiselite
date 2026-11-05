package org.example.artiselite.agent;

import org.example.artiselite.agent.ChatAgent.AgentReply;
import org.example.artiselite.agent.ChatAgent.Status;
import org.example.artiselite.agent.tool.ToolCall;
import org.example.artiselite.entity.Product;
import org.example.artiselite.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calls the REAL OpenAI API (costs a fraction of a cent) against seeded H2 data.
 * Skipped unless explicitly requested:
 *
 *   LIVE_LLM_TEST=true ./mvnw test -Dtest=LiveOpenAiSmokeTest
 *
 * The key is read from OPENAI_API_KEY (environment or the local .env file).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.agent.openai.api-key=${OPENAI_API_KEY:}")
@EnabledIfEnvironmentVariable(named = "LIVE_LLM_TEST", matches = "true")
@Transactional
class LiveOpenAiSmokeTest {

    @Autowired
    private ChatAgent chatAgent;
    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void seed() {
        product("FASH-1", "Denim Jacket", "Fashion Items", 2);
        product("FASH-2", "Silk Scarf", "Fashion Items", 50);
        product("ELEC-1", "USB Cable", "Electronics", 1);
    }

    @Test
    @WithMockUser(username = "live-op", authorities = {"OPERATOR", "INVENTORY_READ", "DASHBOARD_VIEW"})
    void operator_asksAboutLowStock_modelUsesToolsAndAnswersFromData() {
        AgentReply reply = chatAgent.reply(List.of(), "Which fashion items are running low on stock?");
        print(reply);

        assertThat(reply.status()).isEqualTo(Status.ANSWERED);
        assertThat(reply.toolCalls()).extracting(ToolCall::name)
                .containsAnyOf("get_low_stock_items", "search_products");
        assertThat(reply.text()).containsAnyOf("FASH-1", "Denim Jacket");
        assertThat(reply.text()).doesNotContain("USB Cable");
    }

    @Test
    @WithMockUser(username = "live-op", authorities = {"OPERATOR", "INVENTORY_READ", "DASHBOARD_VIEW"})
    void operator_asksForCostData_isNotGivenAny() {
        AgentReply reply = chatAgent.reply(List.of(), "What is the average cost of the Denim Jacket?");
        print(reply);

        assertThat(reply.status()).isEqualTo(Status.ANSWERED);
        // Seeded average cost is 7.35; an Operator's tool results never contain it
        assertThat(reply.text()).doesNotContain("7.35");
    }

    private static void print(AgentReply reply) {
        System.out.println("\n=== LIVE REPLY (" + reply.status() + ") tools=" + reply.toolCalls() + "\n"
                + reply.text() + "\n===");
    }

    private void product(String sku, String name, String category, int quantity) {
        productRepository.save(Product.builder()
                .sku(sku).name(name).category(category)
                .quantity(quantity).lowStockThreshold(10)
                .unitPrice(BigDecimal.TEN).averageCost(new BigDecimal("7.35"))
                .isArchived(false).tags(new HashSet<>())
                .build());
    }
}
