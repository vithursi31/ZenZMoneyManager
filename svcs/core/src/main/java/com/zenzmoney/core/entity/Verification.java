package com.zenzmoney.core.entity;

import com.zenzmoney.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "verification")
public class Verification extends BaseEntity {

    public enum Purpose { VERIFY_EMAIL, RESET_PASSWORD }

    public enum Status { PENDING, VERIFIED, UTILIZED }

    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false, length = 10)
    private String code;

    @Column(nullable = false, length = 30)
    private String purpose;

    @Column(nullable = false, length = 20)
    private String status = Status.PENDING.name();

    @Column(name = "expires_at", nullable = false)
    private long expiresAt;

    @Column(nullable = false)
    private int attempts;
}
