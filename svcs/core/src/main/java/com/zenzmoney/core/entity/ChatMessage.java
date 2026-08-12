package com.zenzmoney.core.entity;

import com.zenzmoney.common.domain.BaseEntity;
import com.zenzmoney.common.domain.ChatMessageStatus;
import com.zenzmoney.common.domain.ChatRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One turn of a capture conversation (domain §3.4). Owned by one user.
 *
 * <p>The transcript is the audit trail for AI-written money: a confirmed
 * transaction traces back through {@link #transactionId} to the exact words the
 * user typed and the draft they accepted.
 *
 * <p>A USER turn is a plain log line. An ASSISTANT turn carries the draft
 * ({@link #parsedIntent}) and the {@link #status} that gates it — the row the
 * confirm and reject calls target.
 */
@Getter
@Setter
@Entity
@Table(name = "chat_message")
public class ChatMessage extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ChatRole role;

    @Column(nullable = false, length = 2000)
    private String content;

    /** BCP-47 tag of the turn, when known (F-1.26). */
    @Column(length = 10)
    private String language;

    /** The draft, on ASSISTANT turns. Null on user turns and on failures. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parsed_intent", columnDefinition = "jsonb")
    private ParsedIntent parsedIntent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ChatMessageStatus status;

    /** Set only once the draft is confirmed — the link into the ledger. */
    @Column(name = "transaction_id", length = 36)
    private String transactionId;

    /** Groups the turns of one conversation. */
    @Column(name = "session_id", length = 36)
    private String sessionId;
}
