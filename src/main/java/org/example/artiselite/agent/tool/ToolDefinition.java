package org.example.artiselite.agent.tool;

import java.util.Map;
import java.util.Set;

/**
 * What the LLM is told about a tool: its name, when to use it, and a JSON Schema for its arguments.
 * The model only ever sees this description; the Java code behind it stays on the server.
 */
public record ToolDefinition(String name, String description, Map<String, Object> parameters) {

    /** The argument names declared in the schema; anything else the model sends is rejected. */
    @SuppressWarnings("unchecked")
    public Set<String> argumentNames() {
        Object properties = parameters.get("properties");
        return properties instanceof Map<?, ?> map ? ((Map<String, Object>) map).keySet() : Set.of();
    }
}
