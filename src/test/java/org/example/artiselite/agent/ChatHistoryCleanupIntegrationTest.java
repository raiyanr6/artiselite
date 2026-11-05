package org.example.artiselite.agent;

import org.example.artiselite.agent.llm.ChatMessage.Role;
import org.example.artiselite.entity.ChatHistoryEntry;
import org.example.artiselite.entity.User;
import org.example.artiselite.repository.ChatHistoryRepository;
import org.example.artiselite.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ChatHistoryCleanupIntegrationTest {

    @Autowired
    private ChatHistoryCleanup cleanup;
    @Autowired
    private ChatHistoryRepository chatHistoryRepository;
    @Autowired
    private UserRepository userRepository;

    @Test
    void deletesOnlyMessagesOlderThan30Days() {
        User user = userRepository.save(User.builder()
                .username("cleanup").email("cleanup@example.com").password("x")
                .role(org.example.artiselite.enums.Role.OPERATOR).isActive(true).permissions(new HashSet<>())
                .build());
        Instant now = Instant.now();
        save(user, "old", now.minus(Duration.ofDays(31)));
        save(user, "recent", now.minus(Duration.ofDays(29)));

        cleanup.deleteOldMessages();

        assertThat(chatHistoryRepository.findAll()).extracting(ChatHistoryEntry::getContent).containsExactly("recent");
    }

    private void save(User user, String content, Instant createdAt) {
        chatHistoryRepository.save(ChatHistoryEntry.builder()
                .user(user).conversationId("00000000-0000-0000-0000-000000000001")
                .role(Role.USER).content(content).createdAt(createdAt)
                .build());
    }
}
