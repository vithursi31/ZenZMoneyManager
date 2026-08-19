package com.zenzmoney.core.web.controller;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.core.service.ChatService;
import com.zenzmoney.core.web.dto.ChatActionRequest;
import com.zenzmoney.core.web.dto.ChatMessageResponse;
import com.zenzmoney.core.web.dto.ChatReplyResponse;
import com.zenzmoney.core.web.dto.ChatRequest;
import com.zenzmoney.core.web.dto.TransactionResponse;
import com.zenzmoney.core.web.dto.UpdateChatDraftRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Chat-based transaction entry (F-1.11). Two steps on purpose: posting a message
 * only ever returns a draft, and a separate confirm writes it to the ledger. A
 * reply that still needs something comes back with the question and the answers to
 * offer for it; {@code /draft} takes the answer, whether it was tapped or edited.
 */
@RestController
@RequestMapping("/api/v1/chat")
@RolesAllowed({"USER", "ADMIN"})
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /** Reads a message into a draft. Never writes to the ledger. */
    @PostMapping
    public ResponseEntity<ApiResponse<ChatReplyResponse>> send(@Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(ApiResponse.success(chatService.handle(request)));
    }

    /**
     * Answers a suggestion, or edits the draft in the preview. Still no ledger write:
     * it returns the same draft, refined, and the next question if one remains.
     */
    @PostMapping("/draft")
    public ResponseEntity<ApiResponse<ChatReplyResponse>> amendDraft(
            @Valid @RequestBody UpdateChatDraftRequest request) {
        return ResponseEntity.ok(ApiResponse.success(chatService.amendDraft(request)));
    }

    /** Commits a draft — the only path from chat to the ledger. */
    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<TransactionResponse>> confirm(@Valid @RequestBody ChatActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(chatService.confirm(request.getMessageId())));
    }

    @PostMapping("/reject")
    public ResponseEntity<ApiResponse<Map<String, String>>> reject(@Valid @RequestBody ChatActionRequest request) {
        chatService.reject(request.getMessageId());
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", "Draft discarded")));
    }

    /** Replays one conversation, oldest turn first. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> history(
            @RequestParam(name = "sessionId", required = false) String sessionId) {
        return ResponseEntity.ok(ApiResponse.success(chatService.history(sessionId)));
    }
}
