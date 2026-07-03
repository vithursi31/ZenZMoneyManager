package com.habit.core.entity;

import com.habit.common.domain.BaseEntity;
import com.habit.common.domain.Role;
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
