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

    @Column(length = 10)
    private String language;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parsed_intent", columnDefinition = "jsonb")
    private ParsedIntent parsedIntent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ChatMessageStatus status;

    @Column(name = "transaction_id", length = 36)
    private String transactionId;

    @Column(name = "recurring_id", length = 36)
    private String recurringId;

    @Column(name = "session_id", length = 36)
    private String sessionId;
}
