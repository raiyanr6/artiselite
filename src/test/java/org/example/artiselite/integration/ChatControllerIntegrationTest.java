package org.example.artiselite.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.artiselite.agent.llm.ChatMessage;
import org.example.artiselite.agent.llm.ChatMessage.Role;
import org.example.artiselite.agent.llm.LlmClient;
import org.example.artiselite.agent.llm.LlmResponse;
import org.example.artiselite.agent.tool.ToolCall;
import org.example.artiselite.dto.auth.RegisterRequest;
import org.example.artiselite.entity.ChatHistoryEntry;
import org.example.artiselite.repository.ChatHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole chat path over HTTP (JWT filter -> controller -> ChatService -> ChatAgent -> tools -> H2),
 * with only the model replaced by a mock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.agent.daily-limit=3")
@Transactional
class ChatControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ChatHistoryRepository chatHistoryRepository;
    @MockBean
    private LlmClient llmClient;
    @MockBean
    private SimpMessagingTemplate messagingTemplate;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        token = registerAndLogin("chatop");
        when(llmClient.complete(anyList(), anyList())).thenReturn(new LlmResponse("Hello from the model", null));
    }

    private String registerAndLogin(String username) throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setEmail(username + "@example.com");
        request.setPassword("password123");
        request.setFullName("Test " + username);
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private ResultActions ask(String jwt, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/chat")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode askOk(String message, String conversationId) throws Exception {
        Map<String, Object> body = conversationId == null
                ? Map.of("message", message)
                : Map.of("message", message, "conversationId", conversationId);
        String json = ask(token, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    @Test
    void withoutToken_isRejected() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().is(anyOf(is(401), is(403))));
        verifyNoInteractions(llmClient);
    }

    @Test
    void question_isAnswered_andBothMessagesAreStored() throws Exception {
        JsonNode reply = askOk("hi", null);

        assertThat(reply.get("reply").asText()).isEqualTo("Hello from the model");
        assertThat(reply.get("conversationId").asText()).hasSize(36);
        assertThat(reply.get("remainingToday").asLong()).isEqualTo(2);

        List<ChatHistoryEntry> rows = chatHistoryRepository.findAll();
        assertThat(rows).extracting(ChatHistoryEntry::getRole).containsExactly(Role.USER, Role.ASSISTANT);
        assertThat(rows.get(1).getStatus()).isEqualTo("ANSWERED");
    }

    @Test
    void toolCall_runsAgainstTheDatabase_andIsRecordedOnTheAnswer() throws Exception {
        when(llmClient.complete(anyList(), anyList()))
                .thenReturn(new LlmResponse(null, List.of(new ToolCall("c1", "get_low_stock_items", "{}"))))
                .thenReturn(new LlmResponse("Nothing is low.", null));

        assertThat(askOk("what's low?", null).get("reply").asText()).isEqualTo("Nothing is low.");

        ChatHistoryEntry answer = chatHistoryRepository.findAll().get(1);
        assertThat(answer.getToolCalls()).contains("get_low_stock_items");
    }

    @Test
    @SuppressWarnings("unchecked")
    void followUp_sendsEarlierMessagesOfThatConversation() throws Exception {
        String conversationId = askOk("first question", null).get("conversationId").asText();
        askOk("follow-up", conversationId);

        ArgumentCaptor<List<ChatMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(llmClient, times(2)).complete(sent.capture(), anyList());
        assertThat(sent.getAllValues().get(1)).extracting(ChatMessage::content)
                .containsSubsequence("first question", "Hello from the model", "follow-up");
    }

    @Test
    void dailyLimit_blocksFurtherQuestions_withoutCallingTheModel() throws Exception {
        askOk("1", null);
        askOk("2", null);
        askOk("3", null);

        ask(token, Map.of("message", "4"))
                .andExpect(status().isTooManyRequests())
                // MockMvc doesn't render the /error body, so check the exception the real server would show
                .andExpect(result -> assertThat(result.getResolvedException())
                        .hasMessageContaining("limit of 3"));
        verify(llmClient, times(3)).complete(any(), any());
    }

    @Test
    void history_returnsOwnConversation_butNothingForAnotherUser() throws Exception {
        String conversationId = askOk("hi", null).get("conversationId").asText();

        mockMvc.perform(get("/api/chat/" + conversationId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[1].content").value("Hello from the model"));

        String otherToken = registerAndLogin("snooper");
        mockMvc.perform(get("/api/chat/" + conversationId).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void invalidInput_isRejectedWith400() throws Exception {
        ask(token, Map.of("message", " ")).andExpect(status().isBadRequest());
        ask(token, Map.of("message", "x".repeat(1001))).andExpect(status().isBadRequest());
        ask(token, Map.of("message", "hi", "conversationId", "not-a-uuid")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/chat/not-a-uuid").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(llmClient);
    }
}
