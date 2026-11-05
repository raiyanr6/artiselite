package org.example.artiselite.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.artiselite.agent.llm.LlmClient.LlmException;
import org.example.artiselite.agent.tool.ToolCall;
import org.example.artiselite.agent.tool.ToolDefinition;
import org.example.artiselite.agent.tool.ToolResult;
import org.example.artiselite.agent.tool.ToolResult.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Checks the translation to and from OpenAI's Chat Completions JSON against a fake HTTP server.
 * No real network calls.
 */
class OpenAiLlmClientTest {

    private static final String URL = "https://api.test/v1/chat/completions";
    private static final List<ToolDefinition> TOOLS = List.of(new ToolDefinition("get_dashboard_stats",
            "Today's summary", Map.of("type", "object", "properties", Map.of())));

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private MockRestServiceServer server;
    private OpenAiLlmClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenAiLlmClient(builder.build(), objectMapper, "sk-test", "test-model", "https://api.test/v1", 800);
    }

    @Test
    void sendsConversationAndTools_inOpenAiFormat() throws Exception {
        AtomicReference<String> sent = new AtomicReference<>();
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-test"))
                .andExpect(req -> sent.set(req.getBody().toString()))
                .andRespond(withSuccess(textReply("hi"), MediaType.APPLICATION_JSON));

        ToolCall call = new ToolCall("call-1", "get_dashboard_stats", "{}");
        client.complete(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("q"),
                ChatMessage.assistantToolCalls(null, List.of(call)),
                ChatMessage.toolResult(new ToolResult("call-1", "get_dashboard_stats", Outcome.SUCCESS, "{\"x\":1}"))),
                TOOLS);

        JsonNode body = objectMapper.readTree(sent.get());
        assertThat(body.get("model").asText()).isEqualTo("test-model");
        assertThat(body.get("max_completion_tokens").asInt()).isEqualTo(800);

        JsonNode messages = body.get("messages");
        assertThat(messages.get(0).get("role").asText()).isEqualTo("system");
        assertThat(messages.get(2).get("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(2).at("/tool_calls/0/id").asText()).isEqualTo("call-1");
        assertThat(messages.get(2).at("/tool_calls/0/function/name").asText()).isEqualTo("get_dashboard_stats");
        assertThat(messages.get(3).get("role").asText()).isEqualTo("tool");
        assertThat(messages.get(3).get("tool_call_id").asText()).isEqualTo("call-1");
        assertThat(messages.get(3).get("content").asText()).isEqualTo("{\"x\":1}");

        assertThat(body.at("/tools/0/type").asText()).isEqualTo("function");
        assertThat(body.at("/tools/0/function/name").asText()).isEqualTo("get_dashboard_stats");
        assertThat(body.at("/tools/0/function/parameters/type").asText()).isEqualTo("object");
        server.verify();
    }

    @Test
    void parsesToolCalls() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"call-9","type":"function","function":{"name":"search_products","arguments":"{\\"query\\":\\"denim\\"}"}}
                ]},"finish_reason":"tool_calls"}]}
                """, MediaType.APPLICATION_JSON));

        LlmResponse response = client.complete(List.of(ChatMessage.user("q")), TOOLS);

        assertThat(response.content()).isNull();
        assertThat(response.toolCalls()).containsExactly(
                new ToolCall("call-9", "search_products", "{\"query\":\"denim\"}"));
    }

    @Test
    void parsesTextAnswer() {
        server.expect(requestTo(URL)).andRespond(withSuccess(textReply("All good."), MediaType.APPLICATION_JSON));

        LlmResponse response = client.complete(List.of(ChatMessage.user("q")), TOOLS);

        assertThat(response.wantsTools()).isFalse();
        assertThat(response.content()).isEqualTo("All good.");
    }

    @Test
    void httpError_becomesLlmException() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.complete(List.of(ChatMessage.user("q")), TOOLS))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("429");
    }

    @Test
    void emptyReply_becomesLlmException() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\"}}]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.complete(List.of(ChatMessage.user("q")), TOOLS))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void missingApiKey_failsWithoutCallingOpenAi() {
        OpenAiLlmClient noKey = new OpenAiLlmClient(RestClient.create(), objectMapper, "", "m", "https://api.test/v1", 800);

        assertThatThrownBy(() -> noKey.complete(List.of(ChatMessage.user("q")), TOOLS))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("OPENAI_API_KEY");
    }

    private static String textReply(String content) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"},"
                + "\"finish_reason\":\"stop\"}]}";
    }
}
