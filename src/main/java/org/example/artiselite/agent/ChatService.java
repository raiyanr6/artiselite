package org.example.artiselite.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.artiselite.agent.ChatAgent.AgentReply;
import org.example.artiselite.agent.llm.ChatMessage;
import org.example.artiselite.agent.llm.ChatMessage.Role;
import org.example.artiselite.common.BusinessTime;
import org.example.artiselite.dto.chat.ChatHistoryItem;
import org.example.artiselite.dto.chat.ChatResponse;
import org.example.artiselite.entity.ChatHistoryEntry;
import org.example.artiselite.entity.User;
import org.example.artiselite.exception.ResourceNotFoundException;
import org.example.artiselite.exception.TooManyRequestsException;
import org.example.artiselite.repository.ChatHistoryRepository;
import org.example.artiselite.repository.UserRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One chat turn, around the agent:
 *   check the daily limit -> load the last few messages -> store the question
 *   -> ChatAgent answers (tool loop) -> store the answer -> reply.
 *
 * Deliberately not @Transactional: the model call can take several seconds, and a database
 * connection shouldn't be held open while waiting for OpenAI. Each save commits on its own.
 */
@Service
public class ChatService {

    static final int HISTORY_PAGE_SIZE = 50;

    private final ChatAgent chatAgent;
    private final ChatHistoryRepository chatHistoryRepository;
    private final UserRepository userRepository;
    private final BusinessTime businessTime;
    private final ObjectMapper objectMapper;
    private final int dailyLimit;
    private final int historyMessages;

    public ChatService(ChatAgent chatAgent, ChatHistoryRepository chatHistoryRepository,
                       UserRepository userRepository, BusinessTime businessTime, ObjectMapper objectMapper,
                       @Value("${app.agent.daily-limit:50}") int dailyLimit,
                       @Value("${app.agent.history-messages:6}") int historyMessages) {
        this.chatAgent = chatAgent;
        this.chatHistoryRepository = chatHistoryRepository;
        this.userRepository = userRepository;
        this.businessTime = businessTime;
        this.objectMapper = objectMapper;
        this.dailyLimit = dailyLimit;
        this.historyMessages = historyMessages;
    }

    public ChatResponse ask(String username, String conversationId, String message) {
        User user = findUser(username);

        long usedToday = chatHistoryRepository.countByUserIdAndRoleAndCreatedAtGreaterThanEqual(
                user.getId(), Role.USER, startOfToday());
        if (usedToday >= dailyLimit) {
            throw new TooManyRequestsException("You have reached today's limit of " + dailyLimit
                    + " chat questions. It resets at midnight (" + businessTime.zone().getId() + ").");
        }

        String convId = conversationId != null ? conversationId.toLowerCase() : UUID.randomUUID().toString();
        // Loaded before saving the new question, so the question isn't sent twice
        List<ChatMessage> history = recentHistory(user, convId);

        chatHistoryRepository.save(ChatHistoryEntry.builder()
                .user(user).conversationId(convId).role(Role.USER).content(message.trim())
                .build());

        AgentReply reply = chatAgent.reply(history, message.trim());

        chatHistoryRepository.save(ChatHistoryEntry.builder()
                .user(user).conversationId(convId).role(Role.ASSISTANT).content(reply.text())
                .toolCalls(toolCallsJson(reply)).status(reply.status().name())
                .build());

        return new ChatResponse(convId, reply.text(), Math.max(0, dailyLimit - usedToday - 1));
    }

    public List<ChatHistoryItem> history(String username, String conversationId) {
        User user = findUser(username);
        List<ChatHistoryEntry> newestFirst = chatHistoryRepository.findByUserIdAndConversationIdOrderByIdDesc(
                user.getId(), conversationId.toLowerCase(), PageRequest.of(0, HISTORY_PAGE_SIZE));
        List<ChatHistoryItem> items = new ArrayList<>(newestFirst.stream()
                .map(e -> new ChatHistoryItem(e.getRole().name(), e.getContent(), e.getCreatedAt()))
                .toList());
        Collections.reverse(items);
        return items;
    }

    // Only the last few messages go to the model: enough for follow-ups ("and last month?"), cheap in tokens
    private List<ChatMessage> recentHistory(User user, String conversationId) {
        List<ChatHistoryEntry> newestFirst = chatHistoryRepository.findByUserIdAndConversationIdOrderByIdDesc(
                user.getId(), conversationId, PageRequest.of(0, historyMessages));
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            ChatHistoryEntry entry = newestFirst.get(i);
            messages.add(entry.getRole() == Role.USER
                    ? ChatMessage.user(entry.getContent())
                    : ChatMessage.assistant(entry.getContent()));
        }
        return messages;
    }

    private String toolCallsJson(AgentReply reply) {
        if (reply.toolCalls().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(reply.toolCalls().stream()
                    .map(c -> Map.of("name", c.name(), "arguments", c.argumentsJson()))
                    .toList());
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private Instant startOfToday() {
        return businessTime.today().atStartOfDay(businessTime.zone()).toInstant();
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
