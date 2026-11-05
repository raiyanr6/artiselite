package org.example.artiselite.agent;

import org.example.artiselite.repository.ChatHistoryRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Deletes chat messages older than the retention period (default 30 days), once a night.
 * Chats can contain business details, so they aren't kept forever.
 */
@Component
public class ChatHistoryCleanup {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryCleanup.class);

    private final ChatHistoryRepository chatHistoryRepository;
    private final int retentionDays;

    public ChatHistoryCleanup(ChatHistoryRepository chatHistoryRepository,
                              @Value("${app.agent.history-retention-days:30}") int retentionDays) {
        this.chatHistoryRepository = chatHistoryRepository;
        this.retentionDays = retentionDays;
    }

    // 03:30 every night in the business timezone (a quiet time for the warehouse)
    @Scheduled(cron = "0 30 3 * * *", zone = "${app.business-timezone:Asia/Kuala_Lumpur}")
    public void deleteOldMessages() {
        int deleted = chatHistoryRepository.deleteOlderThan(Instant.now().minus(Duration.ofDays(retentionDays)));
        log.info("Chat history cleanup: deleted {} messages older than {} days", deleted, retentionDays);
    }
}
