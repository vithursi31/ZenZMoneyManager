package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.Role;
import com.zenzmoney.common.domain.UserStatus;
import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.common.status.ServiceCodes;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.logging.AppLog;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.service.oauth.AppleAuthConnector;
import com.zenzmoney.core.service.oauth.AppleAuthResp;
import com.zenzmoney.core.service.oauth.FacebookAuthConnector;
import com.zenzmoney.core.service.oauth.FacebookAuthResp;
import com.zenzmoney.core.service.oauth.GoogleAuthConnector;
import com.zenzmoney.core.service.oauth.GoogleAuthResp;
import com.zenzmoney.core.util.SupportedLanguages;
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
        User user = findOrCreate("google", resp.getSubject(), resp.getEmail(),
                resp.getFirstName(), resp.getLastName());
        return tokensFor(user);
    }

    @Transactional
    public AuthenticationResponse loginOrRegisterApple(AppleAuthRequest req) {
        AppleAuthResp resp = appleConnector.verifyAuth(req);
        // Names are the only thing taken from the request body, and only to fill a blank
        // profile — Apple reports them to the client on first authorization and never in the
        // token. The email and subject come from the verified token or the sign-in is refused.
        User user = findOrCreate("apple", resp.getSubject(), resp.getEmail(),
                req.getGivenName(), req.getFamilyName());
        return tokensFor(user);
    }

    @Transactional
    public AuthenticationResponse loginOrRegisterFacebook(FacebookAuthRequest req) {
        FacebookAuthResp resp = facebookConnector.verifyAuth(req);
        User user = findOrCreate("facebook", resp.getSubject(), resp.getEmail(),
                resp.getFirstName(), resp.getLastName());
        return tokensFor(user);
    }

    private AuthenticationResponse tokensFor(User user) {
        return new AuthenticationResponse(
                jwtTokenService.generateAccessToken(user.getEmail()),
                jwtTokenService.generateRefreshToken(user.getEmail()));
    }

    /**
     * Resolves the provider's verified identity to an account, creating one on first sign-in.
     *
     * <p><b>Every input here comes from a verified provider response.</b> Nothing falls back to
     * the request body: an email taken from a client would let a caller holding a valid token
     * for their own provider account name any address they liked and receive that user's
     * tokens, which is the account-takeover shape this codebase has already been burned by
     * (see the Security Posture notes). No email means no sign-in.
     *
     * <p>Matched on the provider's subject first, email second. The subject is the stable
     * identity — Apple's private relay address can rotate, and matching on email alone would
     * strand the user with a second, empty account. Email still matches so a password account
     * and a social sign-in for the same verified address stay one account, and so rows written
     * before {@code oauth_subject} existed (V10) are adopted on next sign-in.
     */
    private User findOrCreate(String provider, String subject, String email,
                              String firstName, String lastName) {
        if (email == null || email.isBlank()) {
            audit.warn("OAuth sign-in refused via {} — provider returned no email (subject present={})",
                    provider, subject != null && !subject.isBlank());
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_EMAIL_UNVERIFIED);
        }
        String normalisedEmail = email.toLowerCase();
        // Provider-qualified so one column serves all three and two providers cannot collide
        // on the same opaque id.
        String providerSubject = (subject == null || subject.isBlank()) ? null : provider + ":" + subject;

        Optional<User> bySubject = providerSubject == null
                ? Optional.empty()
                : userRepository.findByOauthSubject(providerSubject);
        if (bySubject.isPresent()) {
            User u = bySubject.get();
            adoptProviderEmail(u, normalisedEmail, provider);
            return touch(u, firstName, lastName, provider, "existing account, matched on subject");
        }

        Optional<User> byEmail = userRepository.findByEmail(normalisedEmail);
        if (byEmail.isPresent()) {
            User u = byEmail.get();
            if (providerSubject != null && u.getOauthSubject() == null) {
                u.setOauthSubject(providerSubject);
            }
            return touch(u, firstName, lastName, provider, "existing account, matched on email");
        }

        byte[] rnd = new byte[24];
        new SecureRandom().nextBytes(rnd);
        String randomPassword = Base64.getEncoder().encodeToString(rnd);

        User u = new User();
        u.setEmail(normalisedEmail);
        u.setOauthSubject(providerSubject);
        u.setPasswordHash(passwordEncoder.encode(randomPassword));
        u.setAuthMode(provider);
        u.setStatus(UserStatus.ACTIVE);
        u.setEmailVerified(true);
        u.setSystemGeneratedPassword(true);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setRoles(Set.of(Role.USER));
        u.setLastLoginTime(System.currentTimeMillis());
        // Same provisional footing as a password signup (F-1.27), minus the currency:
        // none of the three providers reports a locale, so onboarding asks for it.
        u.setLanguage(SupportedLanguages.DEFAULT.toLanguageTag());
        u.setOnboarded(false);
        User saved = userRepository.save(u);
        audit.info("Account registered for {} via {} (user {}, roles={}, language={}, onboarded=false) "
                        + "— email pre-verified by provider",
                normalisedEmail, provider, saved.getId(), saved.getRoles(), saved.getLanguage());
        return saved;
    }

    /**
     * Follows the provider's current address when the subject already identified the account —
     * an Apple relay rotation, or a user who changed their Google address. Refused if another
     * account already holds it: silently moving an address between two accounts is worse than
     * signing in under the old one, and letting the unique constraint decide would surface as a
     * 500 on a working sign-in.
     */
    private void adoptProviderEmail(User u, String normalisedEmail, String provider) {
        if (normalisedEmail.equals(u.getEmail())) {
            return;
        }
        if (userRepository.existsByEmail(normalisedEmail)) {
            audit.warn("OAuth email change ignored via {} (user {}) — another account already holds "
                    + "the address the provider now reports", provider, u.getId());
            return;
        }
        audit.info("OAuth email updated via {} (user {}) — provider now reports a different address",
                provider, u.getId());
        u.setEmail(normalisedEmail);
    }

    private User touch(User u, String firstName, String lastName, String provider, String how) {
        if (u.getFirstName() == null && firstName != null) u.setFirstName(firstName);
        if (u.getLastName() == null && lastName != null) u.setLastName(lastName);
        u.setLastLoginTime(System.currentTimeMillis());
        audit.info("OAuth login succeeded for {} via {} (user {}, {})",
                u.getEmail(), provider, u.getId(), how);
        return userRepository.save(u);
    }
}
