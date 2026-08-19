# Auth Flow — Portable Implementation Guide

A self-contained reference for re-implementing the MindMap AI auth flow in another Spring Boot repo **without infinistack**. Covers:

- Email/password **register** (with OTP verification) + **login** (JWT)
- **Google** OAuth (ID token / auth code)
- **Apple** OAuth (ID token + auth code)
- **Refresh token** endpoint
- JWT request filter, password hashing, password validator

The original code uses an internal `infinistack` framework (`Context`, `Result<T>`, `Session`, `Store<P,Q>`, `TxExecutor`). Below, those are replaced with plain Spring + JPA equivalents so you can paste directly.

---

## 1. Dependencies (Maven)

```xml
<!-- Spring Boot -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-validation</artifactId>
</dependency>

<!-- JWT (jjwt) -->
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-api</artifactId>
  <version>0.11.5</version>
</dependency>
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-impl</artifactId>
  <version>0.11.5</version>
  <scope>runtime</scope>
</dependency>
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-jackson</artifactId>
  <version>0.11.5</version>
  <scope>runtime</scope>
</dependency>

<!-- Password hashing (Bcrypt) -->
<dependency>
  <groupId>com.password4j</groupId>
  <artifactId>password4j</artifactId>
  <version>1.7.3</version>
</dependency>

<!-- For Google/Apple HTTP calls -->
<dependency>
  <groupId>org.asynchttpclient</groupId>
  <artifactId>async-http-client</artifactId>
  <version>2.12.3</version>
</dependency>
```

---

## 2. application.yml — required config

```yaml
app:
  jwt:
    master-key: "REPLACE_WITH_LONG_RANDOM_HS512_SECRET_AT_LEAST_64_BYTES"
    access-token-ttl-ms: 3600000        # 1 hour
    refresh-token-ttl-ms: 2592000000    # 30 days
  google:
    app-id: "<google-client-id-web>.apps.googleusercontent.com"
    app-secret: "<google-client-secret>"
    app-redirect-url: "https://yourdomain.com/login/oauth/google"
    ios-app-id: "<ios-client-id>.apps.googleusercontent.com"
    android-app-id: "<android-client-id>.apps.googleusercontent.com"
  apple:
    client-id: "com.yourapp.ios"          # for mobile
    client-id-web: "com.yourapp.service"  # Service ID for web
    team-id: "ABCDE12345"
    key-id: "F1G2H3I4J5"
    private-key: "<base64-encoded-pkcs8-EC-private-key-from-Apple>"
```

---

## 3. User entity (JPA)

No `loginAttempts` counter on the row — failed-attempt counting lives in the rate
limiter (§11), keyed by account, so it doesn't need a persisted column at all.

`User.java`

```java
package com.yourapp.domain;

import javax.persistence.*;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class User {

    public enum AuthMode { auth_password, auth_google, auth_apple }
    public enum UserStatus { initial, active, expired }
    public enum UserRole { ROLE_SUBSCRIBER, ROLE_ADMIN }

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false, unique = true)
    private String username;       // email, lowercased

    @Column(nullable = false)
    private String password;       // bcrypt hash

    @Enumerated(EnumType.STRING)
    private AuthMode authMode = AuthMode.auth_password;

    @Enumerated(EnumType.STRING)
    private UserStatus status = UserStatus.initial;

    private boolean locked = false;
    private Long lockedTime;
    private Long lastLoginTime;
    private Long lastPasswordChangeTime;
    private boolean systemGeneratedPassword = false;

    private String firstName;
    private String lastName;

    @ElementCollection(fetch = FetchType.EAGER)
    @Enumerated(EnumType.STRING)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    private Set<UserRole> roles = new HashSet<>();

    private Long createdTime = System.currentTimeMillis();
    private Long modifiedTime;

    // getters/setters omitted for brevity
}
```

`UserRepository.java`

```java
package com.yourapp.repo;

import com.yourapp.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
}
```

---

## 4. Password utilities

`PasswordUtils.java`

```java
package com.yourapp.util;

import com.password4j.Password;

public final class PasswordUtils {
    private PasswordUtils() {}

    public static String hashPassword(String plainText) {
        return Password.hash(plainText).withBcrypt().getResult();
    }

    public static boolean matchPassword(String plainText, String hashed) {
        return Password.check(plainText, hashed).withBcrypt();
    }
}
```

`PasswordValidator.java`

```java
package com.yourapp.util;

import java.util.Optional;
import java.util.regex.Pattern;

public final class PasswordValidator {
    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 128;

    private static final Pattern WHITESPACE_PATTERN  = Pattern.compile(".*\\s+.*");
    private static final Pattern LETTER_PATTERN      = Pattern.compile("[A-Za-z]");
    private static final Pattern DIGIT_PATTERN       = Pattern.compile("\\d");
    private static final Pattern SPECIAL_CHAR_PATTERN =
        Pattern.compile("[!@#$%^&*()\\-\\[\\]{}<>.,;:\"'?+=_~`|/\\\\]");

    public static Optional<String> validate(String password) {
        if (password == null || password.isEmpty())          return Optional.of("Password cannot be empty");
        if (WHITESPACE_PATTERN.matcher(password).matches())  return Optional.of("Password cannot contain spaces");
        if (!SPECIAL_CHAR_PATTERN.matcher(password).find())  return Optional.of("Password must contain a special character");
        if (!LETTER_PATTERN.matcher(password).find())        return Optional.of("Password must contain a letter");
        if (!DIGIT_PATTERN.matcher(password).find())         return Optional.of("Password must contain a digit");
        if (password.length() < MIN_LENGTH)                  return Optional.of("Password must be at least " + MIN_LENGTH + " chars");
        if (password.length() > MAX_LENGTH)                  return Optional.of("Password must be at most "  + MAX_LENGTH + " chars");
        return Optional.empty();
    }
}
```

Simple email validator:

```java
package com.yourapp.util;

import java.util.Optional;
import java.util.regex.Pattern;

public final class EmailValidator {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    public static Optional<String> validate(String email) {
        if (email == null || email.isBlank()) return Optional.of("Email is required");
        if (!EMAIL.matcher(email).matches())  return Optional.of("Invalid email");
        return Optional.empty();
    }
}
```

---

## 5. JWT token service

`JwtTokenService.java`

```java
package com.yourapp.service;

import com.yourapp.domain.User;
import com.yourapp.repo.UserRepository;
import io.jsonwebtoken.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class JwtTokenService {

    @Value("${app.jwt.master-key}")              private String masterKey;
    @Value("${app.jwt.access-token-ttl-ms}")     private long accessTtl;
    @Value("${app.jwt.refresh-token-ttl-ms}")    private long refreshTtl;

    private final UserRepository userRepository;

    public JwtTokenService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public String generateAccessToken(String username) {
        return build(username, "access", accessTtl);
    }

    public String generateRefreshToken(String username) {
        return build(username, "refresh", refreshTtl);
    }

    private String build(String username, String type, long ttl) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", username);
        claims.put("type", type);
        return Jwts.builder()
                .setSubject(username)
                .setClaims(claims)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + ttl))
                .signWith(SignatureAlgorithm.HS512, masterKey)
                .compact();
    }

    /** Returns claims if valid AND user exists AND user is active. */
    public Claims extractClaims(String token) {
        Claims claims = Jwts.parser()
                .setSigningKey(masterKey)
                .build()
                .parseClaimsJws(token)
                .getBody();

        String username = claims.get("sub", String.class);
        if (username == null || username.isBlank()) {
            throw new JwtException("Missing subject");
        }

        Optional<User> user = userRepository.findByUsername(username);
        if (user.isEmpty()) {
            throw new JwtException("Account does not exist");
        }
        if (user.get().getStatus() != User.UserStatus.active) {
            throw new JwtException("User not active");
        }
        return claims;
    }
}
```

---

## 6. Spring UserDetailsService

`AppUserDetailsService.java`

```java
package com.yourapp.service;

import com.yourapp.domain.User;
import com.yourapp.repo.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        User u = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        return org.springframework.security.core.userdetails.User.builder()
                .username(u.getUsername())
                .password(u.getPassword())
                .authorities(u.getRoles().stream()
                        .map(r -> new SimpleGrantedAuthority(r.name()))
                        .collect(Collectors.toList()))
                .accountLocked(u.isLocked())
                .accountExpired(u.getStatus() == User.UserStatus.expired)
                .disabled(u.getStatus() != User.UserStatus.active)
                .build();
    }
}
```

---

## 7. JWT auth filter

`JwtAuthenticationFilter.java`

```java
package com.yourapp.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourapp.service.JwtTokenService;
import io.jsonwebtoken.Claims;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/v1/register",
            "/api/v1/authenticate",
            "/api/v1/refresh-token",
            "/api/v1/reset-password",
            "/api/v1/service-status"
    );

    private final JwtTokenService jwtTokenService;
    private final UserDetailsService userDetailsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwtAuthenticationFilter(JwtTokenService jwtTokenService,
                                   UserDetailsService userDetailsService) {
        this.jwtTokenService = jwtTokenService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws IOException, javax.servlet.ServletException {

        String path = req.getServletPath();
        if (!path.startsWith("/api/v1/") ||
            PUBLIC_PATHS.stream().anyMatch(path::startsWith)) {
            chain.doFilter(req, resp);
            return;
        }

        String token = extractToken(req);
        if (token == null) {
            sendError(resp, "NO_TOKEN", "Missing authorization token");
            return;
        }

        try {
            Claims claims = jwtTokenService.extractClaims(token);
            String type = claims.get("type", String.class);
            if (!"access".equals(type)) {
                sendError(resp, "INVALID_TOKEN", "Required access token but found '" + type + "'");
                return;
            }

            String username = claims.getSubject();
            UserDetails details = userDetailsService.loadUserByUsername(username);

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());

            Map<String, Object> meta = new HashMap<>();
            meta.put("sub", username);
            meta.put("type", type);
            meta.put("iat", claims.getIssuedAt().getTime());
            auth.setDetails(meta);

            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(req, resp);
        } catch (Exception e) {
            sendError(resp, "INVALID_TOKEN", e.getMessage());
        }
    }

    private String extractToken(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer")) return h.replaceFirst("Bearer", "").trim();
        String q = req.getParameter("authorization");          // for WebSocket connects
        return (q != null && !q.isEmpty()) ? q : null;
    }

    private void sendError(HttpServletResponse resp, String code, String desc) throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("application/json");
        Map<String, String> body = new HashMap<>();
        body.put("code", code);
        body.put("description", desc);
        objectMapper.writeValue(resp.getOutputStream(), body);
    }
}
```

---

## 8. Spring Security config

`SecurityConfig.java` (Spring Boot 2.7+ / 3.x style)

```java
package com.yourapp.config;

import com.yourapp.web.JwtAuthenticationFilter;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.yourapp.util.PasswordUtils;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtFilter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .antMatchers("/api/v1/register/**",
                             "/api/v1/authenticate/**",
                             "/api/v1/refresh-token",
                             "/api/v1/reset-password/**",
                             "/api/v1/service-status").permitAll()
                .antMatchers("/api/v1/**").authenticated()
                .anyRequest().permitAll()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new PasswordEncoder() {
            @Override public String encode(CharSequence rawPassword) {
                return PasswordUtils.hashPassword(rawPassword.toString());
            }
            @Override public boolean matches(CharSequence rawPassword, String encoded) {
                return PasswordUtils.matchPassword(rawPassword.toString(), encoded);
            }
        };
    }
}
```

---

## 9. DTOs

```java
// AuthenticationRequest.java
public class AuthenticationRequest {
    private String username; private String password;
    // getters/setters
}

// AuthenticationResponse.java
public class AuthenticationResponse {
    private String accessToken; private String refreshToken;
    public AuthenticationResponse(String a, String r) { this.accessToken = a; this.refreshToken = r; }
    // getters
}

// RegisterBeginRequest.java
public class RegisterBeginRequest {
    private String username; private String password;
    // getters/setters
}

// RegisterChallengeRequest.java
public class RegisterChallengeRequest {
    private String id;   // verification id
    private String code; // OTP code
}

// RegisterCompleteRequest.java
public class RegisterCompleteRequest {
    private String username;
    private String verification; // verification id from /begin
    // getters/setters
}

// GoogleAuthRequest.java
public class GoogleAuthRequest {
    public enum GoogleAuthType { AuthCode, IdToken, AccessToken }
    private String value;
    private GoogleAuthType type;
}

// AppleAuthRequest.java
public class AppleAuthRequest {
    private String authorizationCode;
    private String identityToken;
    private String email;       // optional, sent by Apple only on first login
    private String givenName;
    private String familyName;
    private String nonce;
    private boolean isMobileApp;
}
```

---

## 10. Verification (OTP) — simplified

You need an OTP step between `/register/begin` and `/register/complete`. The original uses a `Verification` entity. Minimal version:

`Verification.java`

```java
@Entity @Table(name = "verifications")
public class Verification {
    public enum Status { pending, verified, utilized, expired }

    @Id private String id = UUID.randomUUID().toString();
    private String identifier;           // email
    private String code;                 // 6-digit OTP
    private long expiresAt;
    @Enumerated(EnumType.STRING)
    private Status status = Status.pending;
    @Column(columnDefinition = "TEXT")
    private String dataJson;             // arbitrary JSON (we store hashed password here)
    // getters/setters
}
```

`VerificationService.java`

```java
@Service
public class VerificationService {
    private final VerificationRepository repo;
    private final EmailSender emailSender;     // your own SES/SMTP wrapper
    private final ObjectMapper mapper = new ObjectMapper();

    public VerificationService(VerificationRepository repo, EmailSender emailSender) {
        this.repo = repo; this.emailSender = emailSender;
    }

    public String request(String email, Map<String, Object> data) throws Exception {
        Verification v = new Verification();
        v.setIdentifier(email);
        v.setCode(String.format("%06d", new SecureRandom().nextInt(1_000_000)));
        v.setExpiresAt(System.currentTimeMillis() + 15 * 60_000);  // 15 min
        v.setDataJson(mapper.writeValueAsString(data));
        repo.save(v);
        emailSender.sendOtp(email, v.getCode());
        return v.getId();
    }

    public Verification challenge(String id, String code) {
        Verification v = repo.findById(id).orElseThrow();
        if (v.getStatus() != Verification.Status.pending) throw new IllegalStateException("Already used");
        if (v.getExpiresAt() < System.currentTimeMillis())  throw new IllegalStateException("Expired");
        if (!v.getCode().equals(code))                      throw new IllegalStateException("Wrong code");
        v.setStatus(Verification.Status.verified);
        return repo.save(v);
    }

    public Verification find(String id) { return repo.findById(id).orElseThrow(); }

    public void utilize(Verification v) {
        v.setStatus(Verification.Status.utilized);
        repo.save(v);
    }
}
```

---

## 11. Login service

Wrong-password attempts are throttled by a rate limiter keyed on the account,
not by a counter column on `User` — the same shape as the OTP throttle in §10,
just with its own policy and key prefix (`login:<username>`). Two windows,
checked together: a short one catches a fast brute-force at roughly the same
attempt count a naive counter would; a daily one catches a slow, spaced-out
attacker who'd otherwise dodge the short window entirely. A denial locks the
account the same way a hard-coded attempt cap used to — cleared only by a
password reset, not by time.

`LoginRateLimiter.java`

```java
package com.yourapp.service;

/**
 * Fixed-window rate limiting for login attempts, keyed per account. A minimal
 * single-instance implementation can back this with an in-memory
 * Map<String, Deque<Long>> of attempt timestamps; a multi-instance deployment
 * needs a shared store (Redis) so the limit holds across app instances/restarts.
 */
public interface LoginRateLimiter {
    /** e.g. 5 attempts / 15 min AND 10 attempts / 24h, both enforced together. */
    boolean allow(String key);
}
```

`LoginService.java`

```java
package com.yourapp.service;

import com.yourapp.domain.User;
import com.yourapp.repo.UserRepository;
import com.yourapp.util.PasswordUtils;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class LoginService {

    private final UserRepository userRepo;
    private final LoginRateLimiter rateLimiter;

    public LoginService(UserRepository userRepo, LoginRateLimiter rateLimiter) {
        this.userRepo = userRepo;
        this.rateLimiter = rateLimiter;
    }

    public static class LoginResult {
        public final boolean success;
        public final String  errorCode;
        public final String  message;
        public final User    user;
        private LoginResult(boolean ok, String code, String msg, User u) {
            this.success = ok; this.errorCode = code; this.message = msg; this.user = u;
        }
        public static LoginResult ok(User u)                   { return new LoginResult(true, null, null, u); }
        public static LoginResult fail(String code, String m)  { return new LoginResult(false, code, m, null); }
    }

    public LoginResult login(String usernameRaw, String password) {
        String username = usernameRaw.toLowerCase();
        Optional<User> found = userRepo.findByUsername(username);
        if (found.isEmpty()) return LoginResult.fail("INVALID_USERNAME", "Invalid username or password!");

        User user = found.get();

        if (user.isLocked()) return LoginResult.fail("USER_LOCKED", "Account is locked");

        if (user.getAuthMode() != User.AuthMode.auth_password && user.isSystemGeneratedPassword()) {
            String provider = user.getAuthMode().name().replace("auth_", "");
            provider = Character.toUpperCase(provider.charAt(0)) + provider.substring(1);
            return LoginResult.fail("VALIDATION_FAILED",
                "This account was created using " + provider + " login. Please use 'Login with " + provider + "'.");
        }

        if (user.getStatus() != User.UserStatus.active) {
            return LoginResult.fail("USER_NOT_ACTIVE", "User is not active");
        }

        try {
            if (PasswordUtils.matchPassword(password, user.getPassword())) {
                user.setLastLoginTime(System.currentTimeMillis());
                userRepo.save(user);
                return LoginResult.ok(user);
            }

            if (!rateLimiter.allow("login:" + username)) {
                user.setLocked(true);
                user.setLockedTime(System.currentTimeMillis());
                userRepo.save(user);
                return LoginResult.fail("USER_LOCKED",
                    "Too many failed login attempts. Reset your password to regain access.");
            }
            return LoginResult.fail("INVALID_PASSWORD", "Invalid username or password!");
        } catch (Exception e) {
            return LoginResult.fail("INTERNAL_ERROR", e.getMessage());
        }
    }
}
```

For a production-ready `LoginRateLimiter`, see `RedisRateLimitService` in the
ZenZMoney repo (`svcs/core/.../service/ratelimit/`) — an atomic Lua script over
Redis, fail-closed, shared with the OTP throttle in §10.

---

## 12. Registration service

`RegistrationService.java`

```java
package com.yourapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourapp.domain.User;
import com.yourapp.domain.Verification;
import com.yourapp.repo.UserRepository;
import com.yourapp.util.EmailValidator;
import com.yourapp.util.PasswordUtils;
import com.yourapp.util.PasswordValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class RegistrationService {

    private final UserRepository      userRepo;
    private final VerificationService verificationService;
    private final ObjectMapper        mapper = new ObjectMapper();

    public RegistrationService(UserRepository userRepo, VerificationService verificationService) {
        this.userRepo = userRepo;
        this.verificationService = verificationService;
    }

    /** Step 1: email + password → send OTP. Returns verification id. */
    @Transactional
    public String beginRegister(String emailRaw, String password) throws Exception {
        String email = emailRaw.toLowerCase();

        EmailValidator.validate(email)
            .ifPresent(msg -> { throw new IllegalArgumentException(msg); });

        PasswordValidator.validate(password)
            .ifPresent(msg -> { throw new IllegalArgumentException(msg); });

        if (userRepo.existsByUsername(email)) {
            throw new IllegalArgumentException("Email already in use");
        }

        // Stash hashed password in the verification's data blob so /complete can retrieve it
        return verificationService.request(email,
                Map.of("password", PasswordUtils.hashPassword(password)));
    }

    /** Step 2: validate OTP. */
    @Transactional
    public void challenge(String verificationId, String code) {
        verificationService.challenge(verificationId, code);
    }

    /** Step 3: create user. */
    @Transactional
    public User completeRegister(String emailRaw, String verificationId) throws Exception {
        String email = emailRaw.toLowerCase();

        Verification v = verificationService.find(verificationId);
        if (v.getStatus() != Verification.Status.verified) {
            throw new IllegalStateException("Verification not completed");
        }
        if (!Objects.equals(v.getIdentifier(), email)) {
            throw new IllegalStateException("Email mismatch");
        }
        if (userRepo.existsByUsername(email)) {
            throw new IllegalStateException("Username already taken");
        }

        Map<String, Object> data = mapper.readValue(v.getDataJson(), new TypeReference<>() {});
        String hashedPassword = (String) data.get("password");

        User user = new User();
        user.setUsername(email);
        user.setPassword(hashedPassword);
        user.setStatus(User.UserStatus.active);
        user.setAuthMode(User.AuthMode.auth_password);
        user.setSystemGeneratedPassword(false);
        user.setRoles(Set.of(User.UserRole.ROLE_SUBSCRIBER));

        userRepo.save(user);
        verificationService.utilize(v);
        return user;
    }
}
```

---

## 13. Google OAuth connector

Models:

```java
// GoogleAuthResp.java
public class GoogleAuthResp {
    private String email; private String firstName; private String lastName;
    // getters/setters
}
```

`GoogleAuthConnector.java`

```java
package com.yourapp.service.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourapp.web.GoogleAuthRequest;
import org.asynchttpclient.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;

import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Service
public class GoogleAuthConnector {

    private static final String TOKEN_URL    = "https://oauth2.googleapis.com/token";
    private static final String TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo";
    private static final String USERINFO_URL  = "https://www.googleapis.com/oauth2/v3/userinfo";

    @Value("${app.google.app-id}")           private String appId;
    @Value("${app.google.app-secret}")       private String appSecret;
    @Value("${app.google.app-redirect-url}") private String redirectUrl;
    @Value("${app.google.ios-app-id}")       private String iosAppId;
    @Value("${app.google.android-app-id}")   private String androidAppId;

    private final AsyncHttpClient http = Dsl.asyncHttpClient();
    private final ObjectMapper    mapper = new ObjectMapper();
    private Set<String> validAppIds;

    @PostConstruct
    void init() {
        validAppIds = Set.of(appId, iosAppId, androidAppId);
    }

    public GoogleAuthResp verifyAuth(GoogleAuthRequest req) throws Exception {
        switch (req.getType()) {
            case IdToken:     return validateIdToken(req.getValue());
            case AccessToken: return validateAccessToken(req.getValue());
            case AuthCode:    return validateIdToken(exchangeCodeForIdToken(req.getValue()));
            default: throw new IllegalArgumentException("Unsupported type");
        }
    }

    private String exchangeCodeForIdToken(String code) throws Exception {
        Request req = new RequestBuilder().setUrl(TOKEN_URL).setMethod("POST")
                .setHeader("Accept", "application/json")
                .setHeader("Content-Type", "application/x-www-form-urlencoded")
                .setFormParams(List.of(
                        new Param("code", code),
                        new Param("client_id", appId),
                        new Param("client_secret", appSecret),
                        new Param("redirect_uri", redirectUrl),
                        new Param("grant_type", "authorization_code")))
                .build();
        Map<String, Object> resp = fetch(req);
        return (String) resp.get("id_token");
    }

    private GoogleAuthResp validateIdToken(String idToken) throws Exception {
        Request req = new RequestBuilder().setUrl(TOKENINFO_URL).setMethod("GET")
                .setHeader("Accept", "application/json")
                .setQueryParams(singletonList(new Param("id_token", idToken)))
                .build();
        Map<String, Object> m = fetch(req);

        String aud = (String) m.get("aud");
        String emailVerified = (String) m.get("email_verified");
        String email = (String) m.get("email");
        if (email == null || email.isBlank())            throw new RuntimeException("Email missing");
        if (!Boolean.parseBoolean(emailVerified))         throw new RuntimeException("Email not verified");
        if (!validAppIds.contains(aud))                   throw new RuntimeException("Invalid client id");

        GoogleAuthResp r = new GoogleAuthResp();
        r.setEmail(email);
        r.setFirstName((String) m.get("given_name"));
        r.setLastName((String) m.get("family_name"));
        return r;
    }

    private GoogleAuthResp validateAccessToken(String token) throws Exception {
        Request tokInfo = new RequestBuilder().setUrl(TOKENINFO_URL).setMethod("GET")
                .setHeader("Accept", "application/json")
                .setQueryParams(singletonList(new Param("access_token", token)))
                .build();
        Map<String, Object> ti = fetch(tokInfo);
        if (!validAppIds.contains(ti.get("aud"))) throw new RuntimeException("Invalid client id");

        Request user = new RequestBuilder().setUrl(USERINFO_URL).setMethod("GET")
                .setHeader("Accept", "application/json")
                .setHeader("Authorization", "Bearer " + token)
                .build();
        Map<String, Object> u = fetch(user);

        String email = (String) u.get("email");
        Boolean verified = (Boolean) u.get("email_verified");
        if (email == null || email.isBlank())  throw new RuntimeException("Email missing");
        if (verified == null || !verified)      throw new RuntimeException("Email not verified");

        GoogleAuthResp r = new GoogleAuthResp();
        r.setEmail(email);
        r.setFirstName((String) u.get("given_name"));
        r.setLastName((String) u.get("family_name"));
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetch(Request req) throws Exception {
        Response resp = http.executeRequest(req).get(90_000, MILLISECONDS);
        if (!String.valueOf(resp.getStatusCode()).startsWith("2")) {
            throw new RuntimeException("Google verification failed: " + resp.getStatusCode());
        }
        return mapper.readValue(resp.getResponseBody(), Map.class);
    }
}
```

---

## 14. Apple OAuth connector

`AppleAuthConnector.java`

```java
package com.yourapp.service.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.*;
import com.yourapp.web.AppleAuthRequest;
import io.jsonwebtoken.*;
import org.asynchttpclient.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Service
public class AppleAuthConnector {

    private static final String APPLE_AUTH_URL = "https://appleid.apple.com/auth/token";
    private static final String APPLE_KEYS_URL = "https://appleid.apple.com/auth/keys";
    private static final String APPLE_ISSUER   = "https://appleid.apple.com";

    @Value("${app.apple.client-id}")     private String clientIdMobile;
    @Value("${app.apple.client-id-web}") private String clientIdWeb;
    @Value("${app.apple.team-id}")       private String teamId;
    @Value("${app.apple.key-id}")        private String keyId;
    @Value("${app.apple.private-key}")   private String privateKeyB64;

    private ECPrivateKey privateKey;
    private final AsyncHttpClient http = Dsl.asyncHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private LoadingCache<String, RSAPublicKey> publicKeysCache;

    @PostConstruct
    void init() throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(privateKeyB64);
        this.privateKey = (ECPrivateKey) KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));

        this.publicKeysCache = CacheBuilder.newBuilder()
                .expireAfterWrite(7, TimeUnit.DAYS)
                .build(new CacheLoader<>() {
                    @Override public RSAPublicKey load(String kid) throws Exception {
                        return fetchApplePublicKey(kid);
                    }
                });
    }

    public static class AppleAuthResp {
        private String email;
        public String getEmail() { return email; } public void setEmail(String e) { this.email = e; }
    }

    public AppleAuthResp verifyAuth(AppleAuthRequest req) throws Exception {
        String clientId = req.isMobileApp() ? clientIdMobile : clientIdWeb;
        validateIdToken(req.getIdentityToken(), clientId);
        return exchangeAuthorizationCode(req.getAuthorizationCode(), clientId);
    }

    private void validateIdToken(String idToken, String clientId) throws Exception {
        Claims claims = decodeIdToken(idToken);
        if (!APPLE_ISSUER.equals(claims.getIssuer()))      throw new RuntimeException("Invalid issuer");
        Set<String> aud = claims.getAudience();
        if (aud == null || !aud.contains(clientId))         throw new RuntimeException("Invalid audience");
        Date exp = claims.getExpiration();
        if (exp == null || exp.before(new Date()))          throw new RuntimeException("Token expired");
    }

    @SuppressWarnings("unchecked")
    private Claims decodeIdToken(String idToken) throws Exception {
        String[] parts = idToken.split("\\.");
        String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]));
        Map<String, Object> header = mapper.readValue(headerJson, Map.class);
        String kid = (String) header.get("kid");
        RSAPublicKey pub = publicKeysCache.get(kid);
        return Jwts.parser().setSigningKey(pub).build().parseClaimsJws(idToken).getBody();
    }

    @SuppressWarnings("unchecked")
    private RSAPublicKey fetchApplePublicKey(String kid) throws Exception {
        Request req = new RequestBuilder().setUrl(APPLE_KEYS_URL).setMethod("GET").build();
        Response resp = http.executeRequest(req).get(90_000, MILLISECONDS);
        Map<String, Object> json = mapper.readValue(resp.getResponseBody(), Map.class);
        for (Map<String, String> k : (Iterable<Map<String, String>>) json.get("keys")) {
            if (k.get("kid").equals(kid)) {
                byte[] n = Base64.getUrlDecoder().decode(k.get("n"));
                byte[] e = Base64.getUrlDecoder().decode(k.get("e"));
                return (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(new BigInteger(1, n), new BigInteger(1, e)));
            }
        }
        throw new RuntimeException("Apple key not found for kid: " + kid);
    }

    @SuppressWarnings("unchecked")
    private AppleAuthResp exchangeAuthorizationCode(String code, String clientId) throws Exception {
        String jwt = buildClientSecretJwt(clientId);
        Request req = new RequestBuilder().setUrl(APPLE_AUTH_URL).setMethod("POST")
                .setHeader("Accept", "application/json")
                .setHeader("Content-Type", "application/x-www-form-urlencoded")
                .setBody("client_id="     + URLEncoder.encode(clientId, StandardCharsets.UTF_8) +
                        "&client_secret=" + URLEncoder.encode(jwt, StandardCharsets.UTF_8) +
                        "&code="          + URLEncoder.encode(code, StandardCharsets.UTF_8) +
                        "&grant_type=authorization_code")
                .build();
        Response resp = http.executeRequest(req).get(90_000, MILLISECONDS);
        Map<String, Object> body = mapper.readValue(resp.getResponseBody(), Map.class);
        if (body.containsKey("error")) throw new RuntimeException("Apple error: " + body.get("error"));

        String idToken = (String) body.get("id_token");
        String[] parts = idToken.split("\\.");
        Map<String, Object> payload = mapper.readValue(
                Base64.getUrlDecoder().decode(parts[1]), Map.class);

        AppleAuthResp r = new AppleAuthResp();
        r.setEmail((String) payload.get("email"));
        return r;
    }

    private String buildClientSecretJwt(String clientId) {
        long now = System.currentTimeMillis();
        Map<String, Object> header = new HashMap<>();
        header.put("kid", keyId);
        header.put("alg", "ES256");
        return Jwts.builder()
                .setHeaderParams(header)
                .setHeaderParam("typ", "JWT")
                .setIssuer(teamId)
                .setSubject(clientId)
                .setAudience(APPLE_ISSUER)
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + 86_400_000))
                .signWith(SignatureAlgorithm.ES256, privateKey)
                .compact();
    }
}
```

---

## 15. Google / Apple login service (find-or-create user)

`OAuthLoginService.java`

```java
package com.yourapp.service;

import com.yourapp.domain.User;
import com.yourapp.repo.UserRepository;
import com.yourapp.service.oauth.AppleAuthConnector;
import com.yourapp.service.oauth.GoogleAuthConnector;
import com.yourapp.service.oauth.GoogleAuthResp;
import com.yourapp.web.AppleAuthRequest;
import com.yourapp.web.GoogleAuthRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

@Service
public class OAuthLoginService {

    private final GoogleAuthConnector googleConn;
    private final AppleAuthConnector  appleConn;
    private final UserRepository      userRepo;

    public OAuthLoginService(GoogleAuthConnector g, AppleAuthConnector a, UserRepository r) {
        this.googleConn = g; this.appleConn = a; this.userRepo = r;
    }

    @Transactional
    public User loginOrRegisterGoogle(GoogleAuthRequest req) throws Exception {
        GoogleAuthResp resp = googleConn.verifyAuth(req);
        return findOrCreate(resp.getEmail().toLowerCase(),
                            resp.getFirstName(), resp.getLastName(),
                            User.AuthMode.auth_google);
    }

    @Transactional
    public User loginOrRegisterApple(AppleAuthRequest req) throws Exception {
        AppleAuthConnector.AppleAuthResp resp = appleConn.verifyAuth(req);
        return findOrCreate(resp.getEmail().toLowerCase(),
                            req.getGivenName(), req.getFamilyName(),
                            User.AuthMode.auth_apple);
    }

    private User findOrCreate(String email, String firstName, String lastName, User.AuthMode authMode) {
        Optional<User> existing = userRepo.findByUsername(email);
        if (existing.isPresent()) {
            User u = existing.get();
            if (u.getFirstName() == null && firstName != null) u.setFirstName(firstName);
            if (u.getLastName()  == null && lastName  != null) u.setLastName(lastName);
            u.setLoginAttempts(0);
            u.setLastLoginTime(System.currentTimeMillis());
            return userRepo.save(u);
        }

        byte[] rnd = new byte[16];
        new SecureRandom().nextBytes(rnd);
        String randomPassword = Base64.getEncoder().encodeToString(rnd);

        User u = new User();
        u.setUsername(email);
        u.setPassword(com.yourapp.util.PasswordUtils.hashPassword(randomPassword));
        u.setAuthMode(authMode);
        u.setStatus(User.UserStatus.active);
        u.setSystemGeneratedPassword(true);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setRoles(Set.of(User.UserRole.ROLE_SUBSCRIBER));
        u.setLastLoginTime(System.currentTimeMillis());
        return userRepo.save(u);
    }
}
```

---

## 16. The API controller (the endpoints you asked for)

`AuthController.java`

```java
package com.yourapp.web;

import com.yourapp.domain.User;
import com.yourapp.service.*;
import io.jsonwebtoken.Claims;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final LoginService        loginService;
    private final RegistrationService registrationService;
    private final OAuthLoginService   oauthService;
    private final JwtTokenService     jwtTokenService;

    public AuthController(LoginService l, RegistrationService r,
                          OAuthLoginService o, JwtTokenService j) {
        this.loginService = l; this.registrationService = r;
        this.oauthService = o; this.jwtTokenService = j;
    }

    // ----- Email/password login -----
    @PostMapping("/authenticate")
    public ResponseEntity<?> authenticate(@RequestBody AuthenticationRequest req) {
        LoginService.LoginResult r = loginService.login(req.getUsername(), req.getPassword());
        if (!r.success) return ResponseEntity.status(401).body(Map.of("code", r.errorCode, "description", r.message));
        return ResponseEntity.ok(new AuthenticationResponse(
                jwtTokenService.generateAccessToken(r.user.getUsername()),
                jwtTokenService.generateRefreshToken(r.user.getUsername())));
    }

    // ----- Registration: 3 steps -----
    @PostMapping("/register/begin")
    public ResponseEntity<?> registerBegin(@RequestBody RegisterBeginRequest req) throws Exception {
        String verificationId = registrationService.beginRegister(req.getUsername(), req.getPassword());
        return ResponseEntity.ok(Map.of("verification", verificationId));
    }

    @PostMapping("/register/challenge")
    public ResponseEntity<?> registerChallenge(@RequestBody RegisterChallengeRequest req) {
        registrationService.challenge(req.getId(), req.getCode());
        return ResponseEntity.ok(Map.of("status", "verified"));
    }

    @PostMapping("/register/complete")
    public ResponseEntity<?> registerComplete(@RequestBody RegisterCompleteRequest req) throws Exception {
        User u = registrationService.completeRegister(req.getUsername(), req.getVerification());
        return ResponseEntity.ok(new AuthenticationResponse(
                jwtTokenService.generateAccessToken(u.getUsername()),
                jwtTokenService.generateRefreshToken(u.getUsername())));
    }

    // ----- Google -----
    @PostMapping("/authenticate/google")
    public ResponseEntity<?> google(@RequestBody GoogleAuthRequest req) throws Exception {
        User u = oauthService.loginOrRegisterGoogle(req);
        return ResponseEntity.ok(new AuthenticationResponse(
                jwtTokenService.generateAccessToken(u.getUsername()),
                jwtTokenService.generateRefreshToken(u.getUsername())));
    }

    // ----- Apple -----
    @PostMapping("/authenticate/apple")
    public ResponseEntity<?> apple(@RequestBody AppleAuthRequest req) throws Exception {
        User u = oauthService.loginOrRegisterApple(req);
        return ResponseEntity.ok(new AuthenticationResponse(
                jwtTokenService.generateAccessToken(u.getUsername()),
                jwtTokenService.generateRefreshToken(u.getUsername())));
    }

    // ----- Refresh -----
    @PostMapping("/refresh-token")
    public ResponseEntity<?> refresh(@RequestHeader("Authorization") String auth) {
        if (auth != null && auth.startsWith("Bearer")) auth = auth.replaceFirst("Bearer", "").trim();
        Claims claims = jwtTokenService.extractClaims(auth);
        if (!"refresh".equals(claims.get("type", String.class))) {
            return ResponseEntity.status(401).body(Map.of("code", "INVALID_TOKEN",
                "description", "Required refresh token but found '" + claims.get("type", String.class) + "'"));
        }
        String newAccessToken = jwtTokenService.generateAccessToken(claims.getSubject());
        Map<String, String> body = new HashMap<>();
        body.put("accessToken", newAccessToken);
        return ResponseEntity.ok(body);
    }
}
```

---

## 17. Endpoint summary

| Method | Path                          | Body                                    | Returns                                | Auth |
|--------|-------------------------------|-----------------------------------------|----------------------------------------|------|
| POST   | `/api/v1/authenticate`        | `{ username, password }`                | `{ accessToken, refreshToken }`        | None |
| POST   | `/api/v1/register/begin`      | `{ username, password }`                | `{ verification: <id> }`               | None |
| POST   | `/api/v1/register/challenge`  | `{ id, code }`                          | `{ status: "verified" }`               | None |
| POST   | `/api/v1/register/complete`   | `{ username, verification }`            | `{ accessToken, refreshToken }`        | None |
| POST   | `/api/v1/authenticate/google` | `{ value, type: AuthCode\|IdToken }`    | `{ accessToken, refreshToken }`        | None |
| POST   | `/api/v1/authenticate/apple`  | `{ authorizationCode, identityToken, isMobileApp, ... }` | `{ accessToken, refreshToken }` | None |
| POST   | `/api/v1/refresh-token`       | (header: `Authorization: Bearer <refresh>`) | `{ accessToken }`                  | Refresh token |

All other `/api/v1/**` endpoints require `Authorization: Bearer <accessToken>`.

---

## 18. Auth flow diagrams

### Email/password register
```
client  →  POST /register/begin    {email, password}
        ←  { verification: <id> }
        ←  email containing OTP code

client  →  POST /register/challenge {id, code}
        ←  { status: "verified" }

client  →  POST /register/complete  {email, verification}
        ←  { accessToken, refreshToken }       ← user is now created and logged in
```

### Email/password login
```
client  →  POST /authenticate     {email, password}
        ←  { accessToken, refreshToken }
client  →  GET /api/v1/anything   Authorization: Bearer <accessToken>
```

### Google (web — auth code flow)
```
1. Frontend redirects user to Google OAuth screen
2. Google redirects to your frontend with ?code=...
3. Frontend POSTs to /authenticate/google
   body: { value: "<code>", type: "AuthCode" }
4. Backend:
   - exchanges code for id_token at oauth2.googleapis.com/token
   - validates id_token at oauth2.googleapis.com/tokeninfo
   - finds/creates user, returns JWTs
```

### Google (mobile — id token flow)
```
1. Native SDK gives you an id_token directly
2. POST /authenticate/google { value: "<id_token>", type: "IdToken" }
3. Backend validates id_token + creates/finds user, returns JWTs
```

### Apple
```
1. Apple Sign In gives you an authorization_code and id_token
2. POST /authenticate/apple {
       authorizationCode, identityToken,
       email?, givenName?, familyName?,           // only sent on first sign-in
       isMobileApp: true|false
   }
3. Backend:
   - validates id_token signature against appleid.apple.com/auth/keys
   - exchanges authorization_code at appleid.apple.com/auth/token
     (client_secret is a JWT signed with your EC private key)
   - extracts email from returned id_token, creates/finds user, returns JWTs
```

---

## 19. Things that were stripped because they don't apply

The original codebase wires a lot of side effects into registration that you likely won't need on day one:

- `Workspace` / `Project` / `CreditSchedule` creation
- Referral codes, UTM tracking cookies, signup tracking cookies
- Stripe upgrade-link redirection in the OAuth callback
- `pendingImport` session attribute (for gallery → editor flow)
- `MobileAppFeatureGateService` (mobile doc-processing flag)

Add them back later as your own services — they're orthogonal to auth.

---

## 20. Quick checklist for the new repo

1. Copy/paste `User` + `Verification` entities and repos, run JPA schema generation.
2. Set `app.jwt.master-key` to a strong random string (>= 64 bytes for HS512).
3. Implement `EmailSender.sendOtp(email, code)` (SES, SMTP, SendGrid — your choice).
4. Drop in `JwtTokenService`, `JwtAuthenticationFilter`, `SecurityConfig`, `AppUserDetailsService`.
5. Register a Google OAuth client in Google Cloud Console → fill `app.google.*`.
6. Register an Apple Service ID + key in Apple Developer → fill `app.apple.*` (private key must be base64-encoded PKCS8 of the `.p8` content).
7. Add `LoginService`, `RegistrationService`, `OAuthLoginService`, `AuthController`.
8. Smoke-test:
   - `POST /api/v1/register/begin` → check email arrives
   - `POST /api/v1/register/challenge` → OK
   - `POST /api/v1/register/complete` → returns JWTs
   - `POST /api/v1/authenticate` → returns JWTs
   - `GET /api/v1/<protected>` with `Authorization: Bearer …` → works
