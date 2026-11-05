package org.example.artiselite.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    private String conversationId;
    private String reply;
    private long remainingToday;   // questions left before the daily limit
}
