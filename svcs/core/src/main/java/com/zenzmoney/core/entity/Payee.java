package com.zenzmoney.core.entity;

import com.zenzmoney.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A named merchant or person a transaction is paid to / received from (§1.5b).
 * Owned by one user; modeled as an entity (not a free-text string on the
 * transaction) so payee is a first-class filter/report dimension (F-1.9).
 * Deduped per user by {@link #normalizedName}.
 */
@Getter
@Setter
@Entity
@Table(name = "payee")
public class Payee extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    /** Display name as first entered, e.g. "Keells". */
    @Column(nullable = false, length = 300)
    private String name;

    /** Lower-cased/trimmed/whitespace-collapsed key for matching and uniqueness. */
    @Column(name = "normalized_name", nullable = false, length = 300)
    private String normalizedName;

    @Column(length = 20)
    private String color;

    @Column(length = 50)
    private String icon;
}
