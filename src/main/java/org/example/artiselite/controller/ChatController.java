package org.example.artiselite.controller;

import org.example.artiselite.agent.ChatService;
import org.example.artiselite.dto.chat.ChatHistoryItem;
import org.example.artiselite.dto.chat.ChatRequest;
import org.example.artiselite.dto.chat.ChatResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Chat assistant endpoints. Any logged-in user may chat; what data they get is decided
 * per tool by the same role rules as the rest of the API.
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public ResponseEntity<ChatResponse> ask(@Valid @RequestBody ChatRequest request, Authentication authentication) {
        return ResponseEntity.ok(chatService.ask(
                authentication.getName(), request.getConversationId(), request.getMessage()));
    }

    // The signed-in user's own messages in one conversation, oldest first (last 50)
    @GetMapping("/{conversationId}")
    public ResponseEntity<List<ChatHistoryItem>> history(
            @PathVariable @Pattern(regexp = "^[0-9a-fA-F-]{36}$") String conversationId,
            Authentication authentication) {
        return ResponseEntity.ok(chatService.history(authentication.getName(), conversationId));
    }
}
