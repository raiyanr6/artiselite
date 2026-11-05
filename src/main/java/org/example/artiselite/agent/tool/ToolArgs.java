package org.example.artiselite.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Typed, validated access to the arguments the model sent. Every problem becomes an
 * {@link InvalidToolArgumentsException} whose message is written for the model, so it can fix the call.
 * JSON null is treated the same as "not provided".
 */
public final class ToolArgs {

    public static final int MAX_STRING_LENGTH = 100;

    private final JsonNode node;

    private ToolArgs(JsonNode node) {
        this.node = node;
    }

    public static ToolArgs parse(String json, Set<String> allowedNames, ObjectMapper mapper) {
        JsonNode node;
        try {
            node = (json == null || json.isBlank()) ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new InvalidToolArgumentsException("Arguments are not valid JSON.");
        }
        if (!node.isObject()) {
            throw new InvalidToolArgumentsException("Arguments must be a JSON object.");
        }
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (!allowedNames.contains(name)) {
                throw new InvalidToolArgumentsException(
                        "Unknown argument '" + name + "'. Allowed arguments: " + allowedNames + ".");
            }
        }
        return new ToolArgs(node);
    }

    /** Trimmed string, or null if absent/blank. */
    public String optionalString(String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new InvalidToolArgumentsException("'" + name + "' must be a string.");
        }
        String text = value.asText().trim();
        if (text.length() > MAX_STRING_LENGTH) {
            throw new InvalidToolArgumentsException(
                    "'" + name + "' must be at most " + MAX_STRING_LENGTH + " characters.");
        }
        return text.isEmpty() ? null : text;
    }

    /** Whole number within [min, max], or null if absent. */
    public Integer optionalInt(String name, int min, int max) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new InvalidToolArgumentsException("'" + name + "' must be a whole number.");
        }
        int number = value.asInt();
        if (number < min || number > max) {
            throw new InvalidToolArgumentsException(
                    "'" + name + "' must be between " + min + " and " + max + ".");
        }
        return number;
    }

    public boolean optionalBoolean(String name, boolean defaultValue) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw new InvalidToolArgumentsException("'" + name + "' must be true or false.");
        }
        return value.asBoolean();
    }

    public <E extends Enum<E>> E requiredEnum(String name, Class<E> type) {
        JsonNode value = node.get(name);
        String allowed = Arrays.toString(type.getEnumConstants());
        if (value == null || value.isNull() || !value.isTextual()) {
            throw new InvalidToolArgumentsException("'" + name + "' is required and must be one of " + allowed + ".");
        }
        try {
            return Enum.valueOf(type, value.asText().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidToolArgumentsException("'" + name + "' must be one of " + allowed + ".");
        }
    }

    public static class InvalidToolArgumentsException extends RuntimeException {
        public InvalidToolArgumentsException(String message) {
            super(message);
        }
    }
}
