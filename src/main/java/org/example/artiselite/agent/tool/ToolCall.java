package org.example.artiselite.agent.tool;

/**
 * A request from the LLM to run a tool. The model chooses the name and writes the arguments
 * as a JSON string; nothing here is trusted until ToolExecutor has checked it.
 *
 * @param id            the model's id for this call, echoed back with the result so it can match them up
 * @param name          the tool the model wants to run
 * @param argumentsJson the raw JSON arguments, e.g. {"direction":"OUT","sku":"FASH-1","days":7}
 */
public record ToolCall(String id, String name, String argumentsJson) {
}
