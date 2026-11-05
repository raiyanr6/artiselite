package org.example.artiselite.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.artiselite.agent.tool.ToolArgs.InvalidToolArgumentsException;
import org.example.artiselite.agent.tool.ToolCatalog.RegisteredTool;
import org.example.artiselite.agent.tool.ToolResult.Outcome;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Runs one tool call from the model:
 *   find tool -> check arguments -> run as the current user -> turn the result (or failure) into JSON -> audit.
 * It never throws: every failure becomes a ToolResult the model can read and react to.
 */
@Component
@RequiredArgsConstructor
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    // Safety net on what we send back to the model (tool results are already capped by row limits)
    static final int MAX_RESULT_CHARS = 12_000;

    static final String NOT_PERMITTED_MESSAGE =
            "The current user's role is not allowed to use this tool. Tell the user this information "
                    + "isn't available to their role. Do not retry or try to get it another way.";
    static final String ERROR_MESSAGE =
            "The tool failed because of an internal error. Tell the user the data couldn't be retrieved right now.";

    private final ToolCatalog catalog;
    private final ObjectMapper objectMapper;
    private final ToolCallAuditor auditor;

    public ToolResult execute(ToolCall call) {
        long start = System.nanoTime();
        ToolResult result = run(call);
        auditor.record(call, result, (System.nanoTime() - start) / 1_000_000);
        return result;
    }

    private ToolResult run(ToolCall call) {
        Optional<RegisteredTool> tool = catalog.find(call.name());
        if (tool.isEmpty()) {
            return failure(call, Outcome.UNKNOWN_TOOL, "There is no tool named '" + call.name()
                    + "'. Available tools: " + catalog.definitions().stream().map(ToolDefinition::name).toList() + ".");
        }

        try {
            ToolArgs args = ToolArgs.parse(call.argumentsJson(), tool.get().definition().argumentNames(), objectMapper);
            // Runs on the current thread, so the user's SecurityContext and @PreAuthorize apply
            Object value = tool.get().handler().apply(args);
            String json = objectMapper.writeValueAsString(value);
            if (json.length() > MAX_RESULT_CHARS) {
                return failure(call, Outcome.ERROR,
                        "The result was too large. Narrow the request with a category, SKU or smaller limit.");
            }
            return new ToolResult(call.id(), call.name(), Outcome.SUCCESS, json);
        } catch (InvalidToolArgumentsException e) {
            return failure(call, Outcome.INVALID_ARGUMENTS, e.getMessage());
        } catch (AccessDeniedException | AuthenticationException e) {
            return failure(call, Outcome.NOT_PERMITTED, NOT_PERMITTED_MESSAGE);
        } catch (Exception e) {
            // Full detail stays in the server log; the model only gets a generic message
            log.error("Tool {} failed", call.name(), e);
            return failure(call, Outcome.ERROR, ERROR_MESSAGE);
        }
    }

    private ToolResult failure(ToolCall call, Outcome outcome, String message) {
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of("error", outcome.name(), "message", message));
        } catch (JsonProcessingException e) {
            json = "{\"error\":\"" + outcome.name() + "\"}";
        }
        return new ToolResult(call.id(), call.name(), outcome, json);
    }
}
