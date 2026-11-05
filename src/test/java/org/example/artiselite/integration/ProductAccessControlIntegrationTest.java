package org.example.artiselite.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.artiselite.dto.auth.RegisterRequest;
import org.example.artiselite.dto.product.ProductRequest;
import org.example.artiselite.entity.User;
import org.example.artiselite.enums.Role;
import org.example.artiselite.repository.UserRepository;
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

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the security filter chain actually enforces authentication and
 * role/permission-based authorization on real endpoints.
 *
 * These guard against the historical bug where JwtAuthenticationFilter was
 * never registered into the filter chain and anyRequest().permitAll() left
 * every endpoint open to unauthenticated callers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProductAccessControlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @MockBean
    private SimpMessagingTemplate messagingTemplate;

    private String registerAndLogin(String username, Role role) throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setEmail(username + "@example.com");
        request.setPassword("password123");
        request.setFullName("Test " + username);

        // Public registration only creates OPERATORs; promote other roles directly in the DB.
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

    private long createProduct(String token, String sku) throws Exception {
        ProductRequest product = new ProductRequest();
        product.setName("Item " + sku);
        product.setSku(sku);
        product.setCategory("General");
        product.setQuantity(5);
        product.setUnitPrice(BigDecimal.TEN);
        product.setAverageCost(BigDecimal.valueOf(4));

        String body = mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(product)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void updateProduct_withOperatorToken_isForbidden() throws Exception {
        long id = createProduct(registerAndLogin("mgr-upd", Role.MANAGER), "SKU-UPD-" + System.nanoTime());
        String operatorToken = registerAndLogin("op-upd", Role.OPERATOR);

        ProductRequest update = new ProductRequest();
        update.setName("Hacked");
        update.setSku("ignored");
        update.setCategory("General");
        update.setQuantity(5);

        mockMvc.perform(put("/api/products/" + id)
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isForbidden());
    }

    @Test
    void valuationEndpoints_areForbiddenForOperator_andAllowedForManager() throws Exception {
        String managerToken = registerAndLogin("mgr-val", Role.MANAGER);
        long id = createProduct(managerToken, "SKU-VAL-" + System.nanoTime());
        String operatorToken = registerAndLogin("op-val", Role.OPERATOR);

        mockMvc.perform(get("/api/products/" + id + "/valuation").header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/products/valuation/total").header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/products/" + id + "/valuation").header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());
    }

    @Test
    void productResponse_hidesCostFieldsFromOperator_butNotFromManager() throws Exception {
        String managerToken = registerAndLogin("mgr-cost", Role.MANAGER);
        long id = createProduct(managerToken, "SKU-COST-" + System.nanoTime());
        String operatorToken = registerAndLogin("op-cost", Role.OPERATOR);

        mockMvc.perform(get("/api/products/" + id).header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(5))
                .andExpect(jsonPath("$.averageCost").doesNotExist())
                .andExpect(jsonPath("$.totalValue").doesNotExist());
        mockMvc.perform(get("/api/products/" + id).header("Authorization", "Bearer " + managerToken))
                .andExpect(jsonPath("$.averageCost").value(4))
                .andExpect(jsonPath("$.totalValue").value(20));
        mockMvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalInventoryValue").doesNotExist());
    }

    @Test
    void deletedProduct_isHiddenFromListsAndLookups() throws Exception {
        String adminToken = registerAndLogin("admin-del", Role.ADMIN);
        String sku = "SKU-DEL-" + System.nanoTime();
        long id = createProduct(adminToken, sku);

        mockMvc.perform(delete("/api/products/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/products/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/products").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$[?(@.sku == '" + sku + "')]").isEmpty());
        mockMvc.perform(get("/api/products/low-stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$[?(@.sku == '" + sku + "')]").isEmpty());
    }

    @Test
    void lowStockRead_doesNotBroadcastAnAlert() throws Exception {
        String managerToken = registerAndLogin("mgr-low", Role.MANAGER);
        createProduct(managerToken, "SKU-LOW-" + System.nanoTime()); // qty 5 <= default threshold 10

        mockMvc.perform(get("/api/products/low-stock").header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isNotEmpty());

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void getProducts_withNoToken_isRejected() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().is(anyOf(is(401), is(403))));
    }

    @Test
    void getProducts_withInvalidToken_isRejected() throws Exception {
        mockMvc.perform(get("/api/products")
                        .header("Authorization", "Bearer this.is.not.a.valid.jwt"))
                .andExpect(status().is(anyOf(is(401), is(403))));
    }

    @Test
    void getProducts_withValidToken_succeeds() throws Exception {
        String token = registerAndLogin("operator1", Role.OPERATOR);

        mockMvc.perform(get("/api/products")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void createProduct_withNonAdminNonManagerToken_isForbidden() throws Exception {
        String token = registerAndLogin("operator2", Role.OPERATOR);

        ProductRequest product = new ProductRequest();
        product.setName("Widget");
        product.setSku("SKU-OP-" + System.nanoTime());
        product.setCategory("General");
        product.setQuantity(10);
        product.setUnitPrice(BigDecimal.TEN);

        mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(product)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createProduct_withAdminToken_succeeds() throws Exception {
        String token = registerAndLogin("admin1", Role.ADMIN);

        ProductRequest product = new ProductRequest();
        product.setName("Gadget");
        product.setSku("SKU-ADMIN-" + System.nanoTime());
        product.setCategory("General");
        product.setQuantity(5);
        product.setUnitPrice(BigDecimal.valueOf(19.99));

        mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(product)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value(product.getSku()));
    }

    @Test
    void createProduct_withManagerToken_succeeds() throws Exception {
        String token = registerAndLogin("manager1", Role.MANAGER);

        ProductRequest product = new ProductRequest();
        product.setName("Gizmo");
        product.setSku("SKU-MGR-" + System.nanoTime());
        product.setCategory("General");
        product.setQuantity(3);
        product.setUnitPrice(BigDecimal.valueOf(9.99));

        mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(product)))
                .andExpect(status().isOk());
    }
}
