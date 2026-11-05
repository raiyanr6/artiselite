package org.example.artiselite.integration;

import org.example.artiselite.entity.Product;
import org.example.artiselite.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP wrapper the Python agent calls: same data, same role rules as the Java tools. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AgentToolControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProductRepository productRepository;
    @MockBean
    private SimpMessagingTemplate messagingTemplate;

    @BeforeEach
    void seed() {
        productRepository.save(Product.builder()
                .sku("TOOL-1").name("Hammer").category("Hardware")
                .quantity(2).lowStockThreshold(10)
                .unitPrice(BigDecimal.TEN).averageCost(BigDecimal.valueOf(5))
                .isArchived(false).tags(new HashSet<>())
                .build());
    }

    @Test
    void withoutLogin_isRejected() throws Exception {
        mockMvc.perform(get("/api/agent/tools/low-stock"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser(username = "alice", authorities = "OPERATOR")
    void whoami_returnsTheTokensUser() throws Exception {
        mockMvc.perform(get("/api/agent/tools/whoami"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    @WithMockUser(authorities = "OPERATOR")
    void operator_getsLowStock_withoutCostFields() throws Exception {
        mockMvc.perform(get("/api/agent/tools/low-stock").param("category", "hardware"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sku").value("TOOL-1"))
                .andExpect(jsonPath("$.items[0].averageCost").doesNotExist());
    }

    @Test
    @WithMockUser(authorities = "MANAGER")
    void manager_getsCostFields() throws Exception {
        mockMvc.perform(get("/api/agent/tools/products").param("query", "TOOL-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].averageCost").value(5));
    }

    @Test
    @WithMockUser(authorities = "OPERATOR")
    void transactions_withTooManyDays_isABadRequest() throws Exception {
        mockMvc.perform(get("/api/agent/tools/transactions").param("direction", "OUT").param("days", "500"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "OPERATOR")
    void transactions_lastWeek_returnsTheWindow() throws Exception {
        mockMvc.perform(get("/api/agent/tools/transactions").param("direction", "IN").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.direction").value("IN"))
                .andExpect(jsonPath("$.totalUnits").value(0));
    }
}
