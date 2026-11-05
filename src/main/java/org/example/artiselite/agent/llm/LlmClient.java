package org.example.artiselite.agent.llm;

import org.example.artiselite.agent.tool.ToolDefinition;

import java.util.List;

/**
 * Sends one request to a language model. The rest of the agent only talks to this interface,
 * so tests can swap in a scripted fake and the provider can change without touching the loop.
 */
public interface LlmClient {

    /**
     * @param messages the conversation so far, oldest first
     * @param tools    the tools the model may ask for
     * @throws LlmException if the model can't be reached or replies with something unusable
     */
    LlmResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools);

    class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
