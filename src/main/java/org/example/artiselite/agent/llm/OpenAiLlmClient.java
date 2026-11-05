package org.example.artiselite.agent.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.artiselite.agent.tool.ToolCall;
import org.example.artiselite.agent.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LlmClient for OpenAI's Chat Completions API (POST {base-url}/chat/completions).
 * Translates our ChatMessage/ToolDefinition records to OpenAI's JSON and back; nothing else
 * in the agent knows OpenAI's format.
 *
 * The API key comes from the OPENAI_API_KEY environment variable and is only ever put in the
 * Authorization header: it is never logged. A missing key doesn't stop the app from starting;
 * chat requests just fail with a clear message until it is set.
 */
@Component
public class OpenAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClient.class);
    private static final int MAX_ERROR_BODY_LOGGED = 500;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final int maxOutputTokens;

    @Autowired
    public OpenAiLlmClient(RestClient.Builder builder,
                           ObjectMapper objectMapper,
                           @Value("${app.agent.openai.api-key:}") String apiKey,
                           @Value("${app.agent.openai.model:gpt-4.1-mini}") String model,
                           @Value("${app.agent.openai.base-url:https://api.openai.com/v1}") String baseUrl,
                           @Value("${app.agent.max-output-tokens:800}") int maxOutputTokens,
                           @Value("${app.agent.openai.timeout-seconds:30}") int timeoutSeconds) {
        this(builder.clone().requestFactory(timeouts(timeoutSeconds)).build(),
                objectMapper, apiKey, model, baseUrl, maxOutputTokens);
    }

    // Tests pass a RestClient bound to MockRestServiceServer
    OpenAiLlmClient(RestClient restClient, ObjectMapper objectMapper,
                    String apiKey, String model, String baseUrl, int maxOutputTokens) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public LlmResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("OpenAI API key is not configured (set OPENAI_API_KEY).");
        }

        String responseBody;
        try {
            responseBody = restClient.post()
                    .uri(baseUrl + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(buildRequest(messages, tools)))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            log.warn("OpenAI returned HTTP {}: {}", e.getStatusCode().value(), truncate(e.getResponseBodyAsString()));
            throw new LlmException("OpenAI returned HTTP " + e.getStatusCode().value(), e);
        } catch (RestClientException | JsonProcessingException e) {
            throw new LlmException("Could not reach OpenAI", e);
        }
        return parseResponse(responseBody);
    }

    ObjectNode buildRequest(List<ChatMessage> messages, List<ToolDefinition> tools) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", model);
        request.put("max_completion_tokens", maxOutputTokens);

        ArrayNode jsonMessages = request.putArray("messages");
        for (ChatMessage message : messages) {
            ObjectNode m = jsonMessages.addObject();
            m.put("role", message.role().name().toLowerCase(Locale.ROOT));
            m.put("content", message.content());
            if (message.role() == ChatMessage.Role.TOOL) {
                m.put("tool_call_id", message.toolCallId());
            }
            if (!message.toolCalls().isEmpty()) {
                ArrayNode calls = m.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode c = calls.addObject();
                    c.put("id", call.id());
                    c.put("type", "function");
                    c.putObject("function")
                            .put("name", call.name())
                            .put("arguments", call.argumentsJson());
                }
            }
        }

        if (!tools.isEmpty()) {
            ArrayNode jsonTools = request.putArray("tools");
            for (ToolDefinition tool : tools) {
                ObjectNode function = jsonTools.addObject().put("type", "function").putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.set("parameters", objectMapper.valueToTree(tool.parameters()));
            }
        }
        return request;
    }

    LlmResponse parseResponse(String body) {
        JsonNode message;
        try {
            message = objectMapper.readTree(body).path("choices").path(0).path("message");
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new LlmException("OpenAI reply was not valid JSON", e);
        }
        if (message.isMissingNode()) {
            throw new LlmException("OpenAI reply had no message");
        }

        String content = message.path("content").isTextual() ? message.get("content").asText() : null;
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            toolCalls.add(new ToolCall(
                    call.path("id").asText(),
                    function.path("name").asText(),
                    function.path("arguments").asText("{}")));
        }

        if ((content == null || content.isBlank()) && toolCalls.isEmpty()) {
            throw new LlmException("OpenAI reply was empty");
        }
        return new LlmResponse(content, toolCalls);
    }

    private static SimpleClientHttpRequestFactory timeouts(int seconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(seconds));
        return factory;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String oneLine = value.replaceAll("[\\r\\n]+", " ");
        return oneLine.length() > MAX_ERROR_BODY_LOGGED ? oneLine.substring(0, MAX_ERROR_BODY_LOGGED) + "..." : oneLine;
    }
}
