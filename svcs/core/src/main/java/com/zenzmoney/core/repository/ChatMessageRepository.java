package com.zenzmoney.core.repository;

import com.zenzmoney.common.domain.ChatMessageStatus;
import com.zenzmoney.common.domain.ChatRole;
import com.zenzmoney.core.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, String> {

    /** Ownership-scoped lookup — the only way a confirm/reject may reach a draft (§1.12). */
    Optional<ChatMessage> findByIdAndUserId(String id, String userId);

    /**
     * The last two exchanges of a conversation, newest first — the context the model is
     * given so a follow-up reads as one. Four rows because an exchange is a user turn
     * and an assistant turn; the caller reverses them into reading order.
     */
    List<ChatMessage> findTop4ByUserIdAndSessionIdOrderByCreatedTimeDesc(String userId, String sessionId);

    /** One conversation, oldest first, for replay. */
    List<ChatMessage> findByUserIdAndSessionIdOrderByCreatedTimeAsc(String userId, String sessionId);

    /**
     * The conversation's live draft — the newest assistant turn still in play. At most
     * one exists at a time, because taking a draft further moves the older turn to
     * {@code SUPERSEDED}; the status filter is what keeps a discarded or already-written
     * draft from being picked up as context for the next message.
     */
    Optional<ChatMessage> findFirstByUserIdAndSessionIdAndRoleAndStatusInOrderByCreatedTimeDesc(
            String userId, String sessionId, ChatRole role, Collection<ChatMessageStatus> statuses);
}
