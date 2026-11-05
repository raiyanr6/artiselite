package org.example.artiselite.agent.llm;

import org.example.artiselite.agent.tool.ToolCall;

import java.util.List;

/**
 * What the model sent back for one request: either a final text answer, or tools it wants run
 * (sometimes with a little text alongside).
 */
public record LlmResponse(String content, List<ToolCall> toolCalls) {

    public LlmResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean wantsTools() {
        return !toolCalls.isEmpty();
    }
}
