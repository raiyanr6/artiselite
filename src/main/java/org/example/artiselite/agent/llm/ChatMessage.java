package org.example.artiselite.agent.llm;

import org.example.artiselite.agent.tool.ToolCall;
import org.example.artiselite.agent.tool.ToolResult;

import java.util.List;

/**
 * One message in a conversation with the model, independent of any provider's wire format.
 *
 * @param role       who "said" it
 * @param content    the text (may be null for an assistant message that only requests tools)
 * @param toolCalls  ASSISTANT only: the tools the model asked to run
 * @param toolCallId TOOL only: which tool call this result answers
 */
public record ChatMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public ChatMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content, null, null);
    }

    public static ChatMessage assistantToolCalls(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(Role.ASSISTANT, content, toolCalls, null);
    }

    public static ChatMessage toolResult(ToolResult result) {
        return new ChatMessage(Role.TOOL, result.content(), null, result.callId());
    }
}
