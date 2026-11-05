package org.example.artiselite.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

// One message as shown in the chat panel (tool details stay server-side)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatHistoryItem {
    private String role;      // USER or ASSISTANT
    private String content;
    private Instant createdAt;
}
