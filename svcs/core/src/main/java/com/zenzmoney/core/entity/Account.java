package com.zenzmoney.core.entity;

import com.zenzmoney.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;

/**
 * The user's <b>single</b> container for financial activity (§1.4, F-1.1). Created
 * automatically at onboarding; never named, typed, listed, picked, or deleted.
 *
 * <p><b>It holds no balance.</b> There is no opening or current balance column, so
 * no cached figure can drift out of step with the ledger. What the user sees is the
 * monthly position — {@code Σ INCOME − Σ EXPENSE} for one calendar month, derived on
 * read (§1.10).
 *
 * <p>The entity survives the collapse to one account because ledger rows already
 * carry {@code account_id}: keeping it makes multiple accounts (F-F.1) an additive
 * change instead of a schema rewrite.
 */
@Getter
@Setter
@Entity
@Table(name = "account")
public class Account extends BaseEntity {

    /** Owner. Unique — one account per user, enforced by {@code idx_account_user}. */
    @Column(name = "user_id", nullable = false, length = 36, unique = true)
    private String userId;

    /**
     * ISO-4217. Always the owner's active currency (§0.3); stored per-row so a later
     * multi-currency phase is additive.
     */
    @Column(nullable = false, length = 3)
    private String currency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata = new HashMap<>();
}
