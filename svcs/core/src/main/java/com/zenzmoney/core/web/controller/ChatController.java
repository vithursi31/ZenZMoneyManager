package com.zenzmoney.core.web.controller;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.core.i18n.ChatText;
import com.zenzmoney.core.service.ChatService;
import com.zenzmoney.core.web.dto.ChatActionRequest;
import com.zenzmoney.core.web.dto.ChatMessageResponse;
import com.zenzmoney.core.web.dto.ChatReplyResponse;
import com.zenzmoney.core.web.dto.ChatRequest;
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
import java.util.function.UnaryOperator;

/**
 * Chat-based transaction entry (F-1.11).
 *
 * <p>Posting a message records everything in it the model read completely and
 * confidently, and asks about at most one thing it did not. Nothing needs approving
 * first; {@code /undo} is the way back from a write, and {@code /confirm} exists only
 * for the drafts the model was too unsure to write on its own.
 *
 * <p><b>This is where chat copy becomes text.</b> The service deals in message keys;
 * {@link ChatText} renders them in the caller's language here, which keeps a
 * {@code Locale} out of the service exactly as the exception handler does (§0.5).
 */
@RestController
@RequestMapping("/api/v1/chat")
@RolesAllowed({"USER", "ADMIN"})
public class ChatController {

    private final ChatService chatService;
    private final ChatText chatText;

    public ChatController(ChatService chatService, ChatText chatText) {
        this.chatService = chatService;
        this.chatText = chatText;
    }

    /** Reads a message, records what is complete, and asks about what is not. */
    @PostMapping
    public ResponseEntity<ApiResponse<ChatReplyResponse>> send(@Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(ApiResponse.success(localized(chatService.handle(request))));
    }

    /**
     * Edits the draft in the preview. Deliberately does not write, even when the edit
     * completes the draft — the preview ends at its own Create button.
     */
    @PostMapping("/draft")
    public ResponseEntity<ApiResponse<ChatReplyResponse>> amendDraft(
            @Valid @RequestBody UpdateChatDraftRequest request) {
        return ResponseEntity.ok(ApiResponse.success(localized(chatService.amendDraft(request))));
    }

    /** Writes a draft the model was not confident enough to write on its own. */
    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<ChatReplyResponse>> confirm(@Valid @RequestBody ChatActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(localized(chatService.confirm(request.getMessageId()))));
    }

    /** Deletes what a chat turn wrote — the way back that makes writing unasked safe. */
    @PostMapping("/undo")
    public ResponseEntity<ApiResponse<Void>> undo(@Valid @RequestBody ChatActionRequest request) {
        chatService.undo(request.getMessageId());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** Discards a draft that was never written. */
    @PostMapping("/reject")
    public ResponseEntity<ApiResponse<Void>> reject(@Valid @RequestBody ChatActionRequest request) {
        chatService.reject(request.getMessageId());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** Replays one conversation, oldest turn first. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> history(
            @RequestParam(name = "sessionId", required = false) String sessionId) {
        UnaryOperator<String> text = chatText.forCaller();
        return ResponseEntity.ok(ApiResponse.success(chatService.history(sessionId).stream()
                .map(turn -> turn.localized(text))
                .toList()));
    }

    private ChatReplyResponse localized(ChatReplyResponse reply) {
        return reply.localized(chatText.forCaller());
    }
}
