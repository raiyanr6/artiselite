package org.example.artiselite.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.artiselite.dto.auth.RegisterRequest;
import org.example.artiselite.entity.Product;
import org.example.artiselite.entity.User;
import org.example.artiselite.enums.Role;
import org.example.artiselite.repository.ProductRepository;
import org.example.artiselite.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What the Inbound page receives for unit cost: real login (JWT), real endpoints, by role. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InboundCostVisibilityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProductRepository productRepository;
    @MockBean
    private SimpMessagingTemplate messagingTemplate;

    private Long productId;

    @BeforeEach
    void seed() {
        productId = productRepository.save(Product.builder()
                .sku("COST-1").name("Crate").category("Hardware")
                .quantity(0).lowStockThreshold(1)
                .unitPrice(BigDecimal.TEN).averageCost(BigDecimal.ZERO)
                .isArchived(false).tags(new HashSet<>())
                .build()).getId();
    }

    @Test
    void manager_seesTheUnitCostThatWasSaved() throws Exception {
        String manager = registerAndLogin("costmgr", Role.MANAGER);
        receive(manager, "5.50");

        mockMvc.perform(get("/api/inbound").header("Authorization", "Bearer " + manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].unitCost").value(5.5));
    }

    @Test
    void operator_getsNoUnitCost() throws Exception {
        String manager = registerAndLogin("costmgr2", Role.MANAGER);
        receive(manager, "5.50");
        String operator = registerAndLogin("costop", Role.OPERATOR);

        mockMvc.perform(get("/api/inbound").header("Authorization", "Bearer " + operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].unitCost").doesNotExist());
    }

    private void receive(String jwt, String unitCost) throws Exception {
        mockMvc.perform(post("/api/inbound")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + productId + ",\"quantity\":4,\"unitCost\":" + unitCost + "}"))
                .andExpect(status().isOk());
    }

    private String registerAndLogin(String username, Role role) throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setEmail(username + "@example.com");
        request.setPassword("password123");
        request.setFullName("Test " + username);
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        if (role != Role.OPERATOR) {
            User user = userRepository.findByUsername(username).orElseThrow();
            user.setRole(role);
            userRepository.save(user);
        }
        return objectMapper.readTree(body).get("token").asText();
    }
}
