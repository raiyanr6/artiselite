package org.example.artiselite.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.artiselite.agent.tool.ToolResult.Outcome;
import org.example.artiselite.common.BusinessTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real executor + real tool service + real Spring Security, to prove that going
 * through the executor does not bypass @PreAuthorize.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ToolExecutorIntegrationTest {

    @Autowired
    private ToolExecutor executor;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private BusinessTime businessTime;

    @Test
    @WithMockUser(username = "op", authorities = {"OPERATOR", "OUTBOUND_READ"})
    void permittedUser_getsJsonData_withIsoDates() throws Exception {
        ToolResult result = executor.execute(new ToolCall("c1", "get_transaction_history",
                "{\"direction\":\"OUT\",\"days\":7}"));

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(objectMapper.readTree(result.content()).get("to").asText())
                .isEqualTo(businessTime.today().toString());
    }

    @Test
    @WithMockUser(username = "nobody", authorities = "SOME_UNRELATED_PERMISSION")
    void userWithoutTheRequiredAuthority_getsNotPermitted_notAnException() {
        ToolResult result = executor.execute(new ToolCall("c2", "get_low_stock_items", "{}"));

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_PERMITTED);
    }

    @Test
    void noAuthenticatedUser_getsNotPermitted() {
        ToolResult result = executor.execute(new ToolCall("c3", "get_dashboard_stats", "{}"));

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_PERMITTED);
    }
}
