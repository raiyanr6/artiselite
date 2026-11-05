package org.example.artiselite.agent;

import org.example.artiselite.agent.llm.ChatMessage;
import org.example.artiselite.agent.llm.LlmClient;
import org.example.artiselite.agent.llm.LlmClient.LlmException;
import org.example.artiselite.agent.llm.LlmResponse;
import org.example.artiselite.agent.tool.ToolCall;
import org.example.artiselite.agent.tool.ToolCatalog;
import org.example.artiselite.agent.tool.ToolExecutor;
import org.example.artiselite.agent.tool.ToolResult;
import org.example.artiselite.common.BusinessTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The tool loop. For one user question:
 *   ask the model -> if it wants tools, run them (as the current user) and send back the results -> repeat,
 * until it answers in text or the iteration cap is hit.
 *
 * Runs on the request thread, so every tool call carries the caller's SecurityContext.
 * Stateless: the caller supplies the earlier messages (storing them comes in a later step).
 */
@Service
public class ChatAgent {

    private static final Logger log = LoggerFactory.getLogger(ChatAgent.class);

    static final String ITERATION_LIMIT_MESSAGE =
            "Sorry, I couldn't finish looking that up. Please try a simpler or more specific question.";
    static final String UNAVAILABLE_MESSAGE =
            "The assistant is unavailable right now. Please try again in a few minutes.";

    public enum Status { ANSWERED, ITERATION_LIMIT, LLM_UNAVAILABLE }

    /**
     * @param text      what to show the user
     * @param toolCalls every tool call the model made while answering, in order
     */
    public record AgentReply(String text, Status status, List<ToolCall> toolCalls) {
    }

    private final LlmClient llmClient;
    private final ToolExecutor toolExecutor;
    private final ToolCatalog toolCatalog;
    private final BusinessTime businessTime;
    private final int maxIterations;

    public ChatAgent(LlmClient llmClient, ToolExecutor toolExecutor, ToolCatalog toolCatalog,
                     BusinessTime businessTime, @Value("${app.agent.max-iterations:5}") int maxIterations) {
        this.llmClient = llmClient;
        this.toolExecutor = toolExecutor;
        this.toolCatalog = toolCatalog;
        this.businessTime = businessTime;
        this.maxIterations = maxIterations;
    }

    /**
     * @param history     earlier USER/ASSISTANT messages in this conversation, oldest first (no system message)
     * @param userMessage the new question
     */
    public AgentReply reply(List<ChatMessage> history, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt()));
        messages.addAll(history);
        messages.add(ChatMessage.user(userMessage));

        List<ToolCall> toolCallsMade = new ArrayList<>();
        for (int i = 0; i < maxIterations; i++) {
            LlmResponse response;
            try {
                response = llmClient.complete(messages, toolCatalog.definitions());
            } catch (LlmException e) {
                log.warn("LLM call failed: {}", e.getMessage());
                return new AgentReply(UNAVAILABLE_MESSAGE, Status.LLM_UNAVAILABLE, toolCallsMade);
            }

            if (!response.wantsTools()) {
                return new AgentReply(response.content(), Status.ANSWERED, toolCallsMade);
            }

            // The model must see its own tool request before the results that answer it
            messages.add(ChatMessage.assistantToolCalls(response.content(), response.toolCalls()));
            for (ToolCall call : response.toolCalls()) {
                ToolResult result = toolExecutor.execute(call);
                messages.add(ChatMessage.toolResult(result));
                toolCallsMade.add(call);
            }
        }

        log.warn("Agent hit the {}-iteration limit after {} tool calls", maxIterations, toolCallsMade.size());
        return new AgentReply(ITERATION_LIMIT_MESSAGE, Status.ITERATION_LIMIT, toolCallsMade);
    }

    // Rebuilt on every request so "today" is always current
    String systemPrompt() {
        return """
                You are the assistant for the Artiselite warehouse management system.
                You answer questions about products, stock levels and stock movements using the tools provided.

                Rules:
                - Only state facts that come from tool results. If the tools don't return it, say you don't know.
                - Never guess or estimate numbers. Use the totals the tools compute; don't add up rows yourself.
                - You can only read data. If asked to create, change or delete anything, say you can't, \
                and that it has to be done in the app.
                - If a tool says the user's role isn't permitted, tell them that information isn't available \
                to their role. Don't try to get it another way.
                - If a tool result has a "note", act on it before answering (for example, retry with the \
                exact category name it lists). Never report "none found" when the note says nothing was searched.
                - If a product or category isn't found, say so and suggest how to narrow the search.
                - Keep answers short and plain. Use a small table or list only when it helps.

                Today is %s (timezone %s). "Last week" means the last 7 days including today.
                """.formatted(businessTime.today(), businessTime.zone().getId());
    }
}
