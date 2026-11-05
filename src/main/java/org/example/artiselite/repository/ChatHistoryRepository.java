package org.example.artiselite.repository;

import org.example.artiselite.agent.llm.ChatMessage;
import org.example.artiselite.entity.ChatHistoryEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface ChatHistoryRepository extends JpaRepository<ChatHistoryEntry, Long> {

    // Used for the daily question limit
    long countByUserIdAndRoleAndCreatedAtGreaterThanEqual(Long userId, ChatMessage.Role role, Instant since);

    // Newest first; always scoped to the user so nobody can read another user's conversation
    List<ChatHistoryEntry> findByUserIdAndConversationIdOrderByIdDesc(Long userId, String conversationId,
                                                                      Pageable pageable);

    @Modifying
    @Transactional
    @Query("DELETE FROM ChatHistoryEntry c WHERE c.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
