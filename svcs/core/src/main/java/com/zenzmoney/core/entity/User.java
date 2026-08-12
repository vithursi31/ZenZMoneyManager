package com.zenzmoney.core.entity;

import com.zenzmoney.common.domain.BaseEntity;
import com.zenzmoney.common.domain.Role;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Getter
@Setter
@Entity
@Table(name = "app_user")
public class User extends BaseEntity {

    @Column(unique = true, nullable = false)
    private String email;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "auth_mode", nullable = false, length = 50)
    private String authMode = "password";

    @Column(nullable = false, length = 50)
    private String status = "pending";

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "system_generated_password", nullable = false)
    private boolean systemGeneratedPassword;

    @Column(name = "first_name", length = 120)
    private String firstName;

    @Column(name = "last_name", length = 120)
    private String lastName;

    @Column(nullable = false, length = 50)
    private String timezone = "UTC";

    /**
     * ISO-4217; the user's single active currency (§0.3). Seeded at signup from the
     * locale the client reports, and null when it reported none — read it together
     * with {@link #onboarded}, which says whether the user has confirmed it.
     */
    @Column(name = "active_currency", length = 3)
    private String activeCurrency;

    /** BCP-47 preferred language, e.g. en, ta, si (F-1.26). */
    @Column(length = 10)
    private String language;

    /**
     * Whether the user has confirmed their preferences in onboarding (F-1.27).
     * While false the currency above is a guess and onboarding may still replace it;
     * once true it is frozen, because every stored amount is a minor-unit figure
     * denominated in it and re-denominating would reinterpret them all (§0.3).
     */
    @Column(nullable = false)
    private boolean onboarded;

    private boolean locked;

    @Column(name = "last_login_time")
    private Long lastLoginTime;

    @Column(name = "login_attempts")
    private int loginAttempts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> preferences = new HashMap<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = new HashSet<>();
}
