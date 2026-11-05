package org.example.artiselite.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.artiselite.agent.AgentToolService;
import org.example.artiselite.agent.AgentToolService.Direction;
import org.example.artiselite.agent.dto.ToolResults.ProductListResult;
import org.example.artiselite.agent.tool.ToolResult.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the executor with the tool service mocked, so each failure path can be forced.
 */
class ToolExecutorTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private AgentToolService service;
    private ToolCallAuditor auditor;
    private ToolExecutor executor;

    @BeforeEach
    void setUp() {
        service = mock(AgentToolService.class);
        auditor = mock(ToolCallAuditor.class);
        executor = new ToolExecutor(new ToolCatalog(service), objectMapper, auditor);
    }

    private ToolResult run(String tool, String args) {
        return executor.execute(new ToolCall("call-1", tool, args));
    }

    private String errorMessage(ToolResult result) throws Exception {
        JsonNode json = objectMapper.readTree(result.content());
        assertThat(json.get("error").asText()).isEqualTo(result.outcome().name());
        return json.get("message").asText();
    }

    @Test
    void catalog_exposesExactlyTheFourV1Tools() {
        assertThat(new ToolCatalog(service).definitions()).extracting(ToolDefinition::name)
                .containsExactly("search_products", "get_low_stock_items",
                        "get_dashboard_stats", "get_transaction_history");
    }

    @Test
    void validCall_passesTypedArgumentsToTheService_andReturnsJson() throws Exception {
        when(service.getLowStockItems("Fashion Items", 5))
                .thenReturn(new ProductListResult(null, List.of(), 0, false, null));

        ToolResult result = run("get_low_stock_items", "{\"category\":\" Fashion Items \",\"limit\":5}");

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.callId()).isEqualTo("call-1");
        assertThat(objectMapper.readTree(result.content()).get("totalMatches").asLong()).isZero();
    }

    @Test
    void transactionHistory_defaultsOptionalArguments() {
        run("get_transaction_history", "{\"direction\":\"out\",\"sku\":\"FASH-1\"}");

        verify(service).getTransactionHistory(Direction.OUT, "FASH-1", null, null, false);
    }

    @Test
    void jsonNulls_areTreatedAsNotProvided() {
        run("search_products", "{\"query\":\"denim\",\"category\":null,\"limit\":null}");

        verify(service).searchProducts("denim", null, null);
    }

    @Test
    void unknownTool_isReportedToTheModel_andNothingRuns() throws Exception {
        ToolResult result = run("delete_all_products", "{}");

        assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN_TOOL);
        assertThat(errorMessage(result)).contains("search_products");
        verifyNoInteractions(service);
    }

    @Test
    void malformedJson_isInvalidArguments() {
        assertThat(run("search_products", "{not json").outcome()).isEqualTo(Outcome.INVALID_ARGUMENTS);
        verifyNoInteractions(service);
    }

    @Test
    void undeclaredArgument_isRejected() throws Exception {
        ToolResult result = run("search_products", "{\"query\":\"x\",\"sql\":\"DROP TABLE products\"}");

        assertThat(result.outcome()).isEqualTo(Outcome.INVALID_ARGUMENTS);
        assertThat(errorMessage(result)).contains("Unknown argument 'sql'");
        verifyNoInteractions(service);
    }

    @Test
    void outOfRangeOrWrongTypeValues_areRejected() {
        assertThat(run("get_transaction_history", "{\"direction\":\"OUT\",\"days\":0}").outcome())
                .isEqualTo(Outcome.INVALID_ARGUMENTS);
        assertThat(run("get_transaction_history", "{\"direction\":\"OUT\",\"days\":91}").outcome())
                .isEqualTo(Outcome.INVALID_ARGUMENTS);
        assertThat(run("search_products", "{\"limit\":\"ten\"}").outcome())
                .isEqualTo(Outcome.INVALID_ARGUMENTS);
        assertThat(run("search_products", "{\"query\":\"" + "x".repeat(101) + "\"}").outcome())
                .isEqualTo(Outcome.INVALID_ARGUMENTS);
        assertThat(run("get_transaction_history", "{\"direction\":\"SIDEWAYS\"}").outcome())
                .isEqualTo(Outcome.INVALID_ARGUMENTS);
        verifyNoInteractions(service);
    }

    @Test
    void missingRequiredArgument_isRejected() throws Exception {
        ToolResult result = run("get_transaction_history", "{\"sku\":\"FASH-1\"}");

        assertThat(result.outcome()).isEqualTo(Outcome.INVALID_ARGUMENTS);
        assertThat(errorMessage(result)).contains("'direction' is required");
    }

    @Test
    void accessDenied_becomesACleanNotPermitted() throws Exception {
        when(service.getDashboardStats()).thenThrow(new AccessDeniedException("Access Denied"));

        ToolResult result = run("get_dashboard_stats", "{}");

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_PERMITTED);
        assertThat(errorMessage(result)).isEqualTo(ToolExecutor.NOT_PERMITTED_MESSAGE);
    }

    @Test
    void unexpectedFailure_hidesInternalDetailFromTheModel() throws Exception {
        when(service.getDashboardStats()).thenThrow(new IllegalStateException("JDBC url jdbc:postgresql://secret-host"));

        ToolResult result = run("get_dashboard_stats", "{}");

        assertThat(result.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(result.content()).doesNotContain("secret-host").doesNotContain("Exception");
    }

    @Test
    void everyCall_isAudited_includingFailures() {
        run("get_dashboard_stats", "{}");
        run("no_such_tool", "{}");
        run("search_products", "{bad");

        ArgumentCaptor<ToolResult> results = ArgumentCaptor.forClass(ToolResult.class);
        verify(auditor, times(3)).record(any(ToolCall.class), results.capture(), anyLong());
        assertThat(results.getAllValues()).extracting(ToolResult::outcome)
                .containsExactly(Outcome.SUCCESS, Outcome.UNKNOWN_TOOL, Outcome.INVALID_ARGUMENTS);
    }
}
