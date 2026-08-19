package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.Role;
import com.zenzmoney.common.domain.UserStatus;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.logging.AppLog;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.service.oauth.AppleAuthConnector;
import com.zenzmoney.core.service.oauth.AppleAuthResp;
import com.zenzmoney.core.service.oauth.FacebookAuthConnector;
import com.zenzmoney.core.service.oauth.FacebookAuthResp;
import com.zenzmoney.core.service.oauth.GoogleAuthConnector;
import com.zenzmoney.core.service.oauth.GoogleAuthResp;
import com.zenzmoney.core.web.dto.AppleAuthRequest;
import com.zenzmoney.core.web.dto.AuthenticationResponse;
import com.zenzmoney.core.web.dto.FacebookAuthRequest;
import com.zenzmoney.core.web.dto.GoogleAuthRequest;
import org.slf4j.Logger;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

@Service
public class OAuthLoginService {

    /** Provider sign-ins, audited at the one seam all three providers pass through. */
    private static final Logger audit = AppLog.AUDIT;

    private final GoogleAuthConnector googleConnector;
    private final AppleAuthConnector appleConnector;
    private final FacebookAuthConnector facebookConnector;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    public OAuthLoginService(GoogleAuthConnector googleConnector,
                             AppleAuthConnector appleConnector,
                             FacebookAuthConnector facebookConnector,
                             UserRepository userRepository,
                             PasswordEncoder passwordEncoder,
                             JwtTokenService jwtTokenService) {
        this.googleConnector = googleConnector;
        this.appleConnector = appleConnector;
        this.facebookConnector = facebookConnector;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
    }

    @Transactional
    public AuthenticationResponse loginOrRegisterGoogle(GoogleAuthRequest req) {
        GoogleAuthResp resp = googleConnector.verifyAuth(req);
        User user = findOrCreate(resp.getEmail().toLowerCase(),
                resp.getFirstName(), resp.getLastName(), "google");
        return tokensFor(user);
    }

    @Transactional
    public AuthenticationResponse loginOrRegisterApple(AppleAuthRequest req) {
        AppleAuthResp resp = appleConnector.verifyAuth(req);
        String email = resp.getEmail() != null ? resp.getEmail() : req.getEmail();
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("Apple did not return an email and none was provided");
        }
        User user = findOrCreate(email.toLowerCase(),
                req.getGivenName(), req.getFamilyName(), "apple");
        return tokensFor(user);
    }

    @Transactional
    public AuthenticationResponse loginOrRegisterFacebook(FacebookAuthRequest req) {
        FacebookAuthResp resp = facebookConnector.verifyAuth(req);
        User user = findOrCreate(resp.getEmail().toLowerCase(),
                resp.getFirstName(), resp.getLastName(), "facebook");
        return tokensFor(user);
    }

    private AuthenticationResponse tokensFor(User user) {
        return new AuthenticationResponse(
                jwtTokenService.generateAccessToken(user.getEmail()),
                jwtTokenService.generateRefreshToken(user.getEmail()));
    }

    private User findOrCreate(String email, String firstName, String lastName, String authMode) {
        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            User u = existing.get();
            if (u.getFirstName() == null && firstName != null) u.setFirstName(firstName);
            if (u.getLastName()  == null && lastName  != null) u.setLastName(lastName);
            u.setLastLoginTime(System.currentTimeMillis());
            audit.info("OAuth login succeeded for {} via {} (user {}, existing account)",
                    email, authMode, u.getId());
            return userRepository.save(u);
        }

        byte[] rnd = new byte[24];
        new SecureRandom().nextBytes(rnd);
        String randomPassword = Base64.getEncoder().encodeToString(rnd);

        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(passwordEncoder.encode(randomPassword));
        u.setAuthMode(authMode);
        u.setStatus(UserStatus.ACTIVE);
        u.setEmailVerified(true);
        u.setSystemGeneratedPassword(true);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setRoles(Set.of(Role.USER));
        u.setLastLoginTime(System.currentTimeMillis());
        // Same provisional footing as a password signup (F-1.27), minus the currency:
        // none of the three providers reports a locale, so onboarding asks for it.
        u.setLanguage(SignupDefaults.LANGUAGE);
        u.setOnboarded(false);
        User saved = userRepository.save(u);
        audit.info("Account registered for {} via {} (user {}, roles={}, language={}, onboarded=false) "
                        + "— email pre-verified by provider",
                email, authMode, saved.getId(), saved.getRoles(), saved.getLanguage());
        return saved;
    }
}
