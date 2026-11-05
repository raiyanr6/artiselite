package org.example.artiselite.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatRequest {
    @NotBlank(message = "Message is required")
    @Size(max = 1000, message = "Message must be at most 1000 characters")
    private String message;

    // Omit to start a new conversation; send back the id from the previous reply to continue one
    @Pattern(regexp = "^[0-9a-fA-F-]{36}$", message = "conversationId must be a UUID")
    private String conversationId;
}
