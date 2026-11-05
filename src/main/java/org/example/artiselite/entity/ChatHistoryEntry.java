package org.example.artiselite.entity;

import org.example.artiselite.agent.llm.ChatMessage;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One stored chat message: either the user's question (USER) or the assistant's answer (ASSISTANT).
 * Tool results are not stored, only which tools were called and with what arguments.
 * Never holds the JWT. Rows older than the retention period are deleted by ChatHistoryCleanup.
 */
@Entity
@Table(name = "chat_messages", indexes = {
        @Index(name = "idx_chat_messages_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_chat_messages_conversation", columnList = "conversation_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatHistoryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChatMessage.Role role;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    // ASSISTANT only: JSON list of {"name": ..., "arguments": ...} for each tool the model called
    @Column(name = "tool_calls", columnDefinition = "TEXT")
    private String toolCalls;

    // ASSISTANT only: ChatAgent.Status (ANSWERED, ITERATION_LIMIT, LLM_UNAVAILABLE)
    @Column(length = 20)
    private String status;

    // An exact instant (not server-local time), so "today" can be computed in the business timezone
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
