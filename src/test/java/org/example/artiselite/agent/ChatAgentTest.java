package org.example.artiselite.agent;

import org.example.artiselite.agent.ChatAgent.AgentReply;
import org.example.artiselite.agent.ChatAgent.Status;
import org.example.artiselite.agent.llm.ChatMessage;
import org.example.artiselite.agent.llm.ChatMessage.Role;
import org.example.artiselite.agent.llm.LlmClient;
import org.example.artiselite.agent.llm.LlmClient.LlmException;
import org.example.artiselite.agent.llm.LlmResponse;
import org.example.artiselite.agent.tool.ToolCall;
import org.example.artiselite.agent.tool.ToolCatalog;
import org.example.artiselite.agent.tool.ToolDefinition;
import org.example.artiselite.agent.tool.ToolExecutor;
import org.example.artiselite.agent.tool.ToolResult;
import org.example.artiselite.agent.tool.ToolResult.Outcome;
import org.example.artiselite.common.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the tool loop with a scripted fake model and a mocked executor.
 */
class ChatAgentTest {

    /** Plays back a fixed list of replies and records what it was sent each time. */
    static class ScriptedLlm implements LlmClient {
        final Deque<Object> script = new ArrayDeque<>();
        final List<List<ChatMessage>> requests = new ArrayList<>();

        ScriptedLlm then(Object replyOrException) {
            script.add(replyOrException);
            return this;
        }

        @Override
        public LlmResponse complete(List<ChatMessage> messages, List<ToolDefinition> tools) {
            requests.add(List.copyOf(messages));
            Object next = script.isEmpty() ? toolRequest("again") : script.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (LlmResponse) next;
        }
    }

    private static LlmResponse text(String content) {
        return new LlmResponse(content, null);
    }

    private static LlmResponse toolRequest(String id) {
        return new LlmResponse(null, List.of(new ToolCall(id, "get_low_stock_items", "{}")));
    }

    private ScriptedLlm llm;
    private ToolExecutor executor;
    private ChatAgent agent;

    @BeforeEach
    void setUp() {
        llm = new ScriptedLlm();
        executor = mock(ToolExecutor.class);
        when(executor.execute(any())).thenAnswer(inv -> {
            ToolCall call = inv.getArgument(0);
            return new ToolResult(call.id(), call.name(), Outcome.SUCCESS, "{\"items\":[]}");
        });
        BusinessTime time = new BusinessTime(Clock.fixed(
                Instant.parse("2026-10-26T01:00:00Z"), ZoneId.of("Asia/Kuala_Lumpur")));
        agent = new ChatAgent(llm, executor, new ToolCatalog(mock(AgentToolService.class)), time, 5);
    }

    @Test
    void plainAnswer_returnsItWithoutRunningTools() {
        llm.then(text("Hello!"));

        AgentReply reply = agent.reply(List.of(), "hi");

        assertThat(reply.status()).isEqualTo(Status.ANSWERED);
        assertThat(reply.text()).isEqualTo("Hello!");
        assertThat(reply.toolCalls()).isEmpty();
        verifyNoInteractions(executor);
    }

    @Test
    void toolRequest_isRun_andResultSentBackWithMatchingId() {
        llm.then(toolRequest("call-1")).then(text("Nothing is low on stock."));

        AgentReply reply = agent.reply(List.of(), "what's low?");

        assertThat(reply.text()).isEqualTo("Nothing is low on stock.");
        assertThat(reply.toolCalls()).extracting(ToolCall::id).containsExactly("call-1");

        List<ChatMessage> second = llm.requests.get(1);
        assertThat(second).extracting(ChatMessage::role)
                .containsExactly(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL);
        assertThat(second.get(2).toolCalls()).extracting(ToolCall::id).containsExactly("call-1");
        assertThat(second.get(3).toolCallId()).isEqualTo("call-1");
        assertThat(second.get(3).content()).isEqualTo("{\"items\":[]}");
    }

    @Test
    void history_isSentBetweenSystemPromptAndNewQuestion() {
        llm.then(text("ok"));

        agent.reply(List.of(ChatMessage.user("earlier q"), ChatMessage.assistant("earlier a")), "new q");

        assertThat(llm.requests.get(0)).extracting(ChatMessage::content)
                .last().isEqualTo("new q");
        assertThat(llm.requests.get(0)).extracting(ChatMessage::role)
                .containsExactly(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.USER);
    }

    @Test
    void modelThatNeverStops_isCutOffAtTheIterationCap() {
        // Script is empty, so the fake keeps asking for tools forever
        AgentReply reply = agent.reply(List.of(), "loop");

        assertThat(reply.status()).isEqualTo(Status.ITERATION_LIMIT);
        assertThat(reply.text()).isEqualTo(ChatAgent.ITERATION_LIMIT_MESSAGE);
        assertThat(llm.requests).hasSize(5);
        verify(executor, times(5)).execute(any());
    }

    @Test
    void llmFailure_givesFriendlyMessage() {
        llm.then(new LlmException("HTTP 500"));

        AgentReply reply = agent.reply(List.of(), "hi");

        assertThat(reply.status()).isEqualTo(Status.LLM_UNAVAILABLE);
        assertThat(reply.text()).isEqualTo(ChatAgent.UNAVAILABLE_MESSAGE).doesNotContain("500");
    }

    @Test
    void systemPrompt_usesBusinessDateNotServerDate() {
        assertThat(agent.systemPrompt()).contains("Today is 2026-10-26 (timezone Asia/Kuala_Lumpur)");
    }
}
