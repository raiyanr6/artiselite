package org.example.artiselite.agent.tool;

/**
 * What goes back to the LLM after a tool call. {@code content} is always JSON: the tool's data
 * on success, or {"error": ..., "message": ...} otherwise. Never a stack trace.
 */
public record ToolResult(String callId, String toolName, Outcome outcome, String content) {

    public enum Outcome {
        SUCCESS,
        NOT_PERMITTED,      // the user's role may not run this tool
        INVALID_ARGUMENTS,  // the model sent arguments that don't match the schema
        UNKNOWN_TOOL,       // the model asked for a tool that doesn't exist
        ERROR               // something failed on our side
    }

    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }
}
