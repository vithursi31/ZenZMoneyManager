package com.zenzmoney.core.repository;

import com.zenzmoney.core.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, String> {

    /** Ownership-scoped lookup — the only way a confirm/reject may reach a draft (§1.12). */
    Optional<ChatMessage> findByIdAndUserId(String id, String userId);

    /** One conversation, oldest first, for replay. */
    List<ChatMessage> findByUserIdAndSessionIdOrderByCreatedTimeAsc(String userId, String sessionId);
}
