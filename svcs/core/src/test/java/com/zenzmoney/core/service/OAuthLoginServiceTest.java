package com.zenzmoney.core.service;

import com.zenzmoney.common.exception.ServiceException;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.service.oauth.AppleAuthConnector;
import com.zenzmoney.core.service.oauth.AppleAuthResp;
import com.zenzmoney.core.service.oauth.FacebookAuthConnector;
import com.zenzmoney.core.service.oauth.GoogleAuthConnector;
import com.zenzmoney.core.service.oauth.GoogleAuthResp;
import com.zenzmoney.core.web.dto.AppleAuthRequest;
import com.zenzmoney.core.web.dto.AuthenticationResponse;
import com.zenzmoney.core.web.dto.GoogleAuthRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which account a social sign-in resolves to is decided here, so this is where the
 * provider's word has to be the only input. The first test is a regression: the service
 * used to fall back to an {@code email} field on the request body when the provider
 * returned none, which let a caller holding a valid token for their own provider account
 * name any address they liked and be handed that user's tokens.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OAuthLoginServiceTest {

    @Mock GoogleAuthConnector googleConnector;
    @Mock AppleAuthConnector appleConnector;
    @Mock FacebookAuthConnector facebookConnector;
    @Mock UserRepository userRepository;
    @Mock JwtTokenService jwtTokenService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private OAuthLoginService service() {
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtTokenService.generateAccessToken(anyString())).thenReturn("access-token");
        when(jwtTokenService.generateRefreshToken(anyString())).thenReturn("refresh-token");
        return new OAuthLoginService(googleConnector, appleConnector, facebookConnector,
                userRepository, passwordEncoder, jwtTokenService);
    }

    private static AppleAuthResp appleResp(String subject, String email) {
        AppleAuthResp r = new AppleAuthResp();
        r.setSubject(subject);
        r.setEmail(email);
        return r;
    }

    private static GoogleAuthResp googleResp(String subject, String email) {
        GoogleAuthResp r = new GoogleAuthResp();
        r.setSubject(subject);
        r.setEmail(email);
        r.setFirstName("Given");
        r.setLastName("Family");
        return r;
    }

    private static User existing(String id, String email, String oauthSubject) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setOauthSubject(oauthSubject);
        return u;
    }

    /**
     * The takeover. Apple verifies the attacker's own token, so the connector succeeds — it
     * just has no email to report. Nothing may resolve an account from that: not the request
     * body, not a lookup by anything the caller controls.
     */
    @Test
    void appleSignInWithNoProviderEmail_isRefusedAndResolvesNoAccount() {
        when(appleConnector.verifyAuth(any())).thenReturn(appleResp("attacker-sub", null));

        AppleAuthRequest req = new AppleAuthRequest();
        req.setIdentityToken("token-valid-for-the-attackers-own-apple-id");
        req.setGivenName("Attacker");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service().loginOrRegisterApple(req));

        assertEquals("E1072", ex.getStatusCode().code());
        assertEquals(401, ex.getStatusCode().httpStatus());
        // The old fallback resolved the account by email. Nothing looks anything up now.
        verify(userRepository, never()).findByEmail(anyString());
        verify(userRepository, never()).findByOauthSubject(anyString());
        verify(userRepository, never()).save(any(User.class));
        verify(jwtTokenService, never()).generateAccessToken(anyString());
    }

    /** Same guard on the other two paths — the rule is the provider's word or nothing. */
    @Test
    void googleSignInWithNoProviderEmail_isRefused() {
        when(googleConnector.verifyAuth(any())).thenReturn(googleResp("google-sub", "  "));

        GoogleAuthRequest req = new GoogleAuthRequest();
        req.setType(GoogleAuthRequest.GoogleAuthType.IdToken);
        req.setValue("id-token");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service().loginOrRegisterGoogle(req));
        assertEquals("E1072", ex.getStatusCode().code());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void firstSignIn_createsAnUnonboardedUserWithAProviderQualifiedSubject() {
        when(appleConnector.verifyAuth(any())).thenReturn(appleResp("0012.abc", "New@Example.com"));
        when(userRepository.findByOauthSubject("apple:0012.abc")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

        AppleAuthRequest req = new AppleAuthRequest();
        req.setIdentityToken("t");
        req.setGivenName("Given");
        req.setFamilyName("Family");

        AuthenticationResponse tokens = service().loginOrRegisterApple(req);
        assertNotNull(tokens);

        User saved = savedUser();
        assertEquals("new@example.com", saved.getEmail(), "email is normalised to lower case");
        assertEquals("apple:0012.abc", saved.getOauthSubject(), "qualified so providers cannot collide");
        assertEquals("apple", saved.getAuthMode());
        assertTrue(saved.isEmailVerified());
        assertFalse(saved.isOnboarded(),
                "onboarding still has to ask for a currency — no provider reports one");
        // Names are the one thing read off the request — Apple never puts them in the token.
        assertEquals("Given", saved.getFirstName());
        assertEquals("Family", saved.getLastName());
    }

    /**
     * The reason the subject column exists: Apple's private relay address rotates, and an
     * email-only match would strand the user with a second, empty account.
     */
    @Test
    void appleRelayRotation_matchesOnSubjectAndFollowsTheNewAddress() {
        User user = existing("u1", "old@privaterelay.appleid.com", "apple:0012.abc");
        when(appleConnector.verifyAuth(any())).thenReturn(appleResp("0012.abc", "new@privaterelay.appleid.com"));
        when(userRepository.findByOauthSubject("apple:0012.abc")).thenReturn(Optional.of(user));
        when(userRepository.existsByEmail("new@privaterelay.appleid.com")).thenReturn(false);

        AppleAuthRequest req = new AppleAuthRequest();
        req.setIdentityToken("t");
        service().loginOrRegisterApple(req);

        assertEquals("new@privaterelay.appleid.com", user.getEmail());
        assertNotNull(user.getLastLoginTime());
        verify(userRepository, never()).findByEmail(anyString());
    }

    /**
     * A rotation onto an address someone else already holds signs the user in under the old
     * one rather than merging two accounts — and rather than letting the unique constraint
     * turn a working sign-in into a 500.
     */
    @Test
    void appleRelayRotation_keepsTheOldAddressWhenAnotherAccountHoldsTheNewOne() {
        User user = existing("u1", "old@privaterelay.appleid.com", "apple:0012.abc");
        when(appleConnector.verifyAuth(any())).thenReturn(appleResp("0012.abc", "taken@example.com"));
        when(userRepository.findByOauthSubject("apple:0012.abc")).thenReturn(Optional.of(user));
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        AppleAuthRequest req = new AppleAuthRequest();
        req.setIdentityToken("t");
        assertNotNull(service().loginOrRegisterApple(req));

        assertEquals("old@privaterelay.appleid.com", user.getEmail(), "no silent account merge");
    }

    /** A row written before V10 has no subject; it is adopted rather than duplicated. */
    @Test
    void existingAccountWithoutASubject_adoptsItOnNextSignIn() {
        User user = existing("u1", "someone@example.com", null);
        when(googleConnector.verifyAuth(any())).thenReturn(googleResp("g-123", "someone@example.com"));
        when(userRepository.findByOauthSubject("google:g-123")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("someone@example.com")).thenReturn(Optional.of(user));

        GoogleAuthRequest req = new GoogleAuthRequest();
        req.setType(GoogleAuthRequest.GoogleAuthType.IdToken);
        req.setValue("id-token");
        service().loginOrRegisterGoogle(req);

        assertEquals("google:g-123", user.getOauthSubject());
        assertEquals("Given", user.getFirstName(), "a blank profile is filled from the provider");
    }

    /** A provider that reports no subject still resolves on email — it must not look one up. */
    @Test
    void providerWithoutASubject_fallsBackToEmailMatching() {
        User user = existing("u1", "someone@example.com", null);
        when(googleConnector.verifyAuth(any())).thenReturn(googleResp(null, "someone@example.com"));
        when(userRepository.findByEmail("someone@example.com")).thenReturn(Optional.of(user));

        GoogleAuthRequest req = new GoogleAuthRequest();
        req.setType(GoogleAuthRequest.GoogleAuthType.IdToken);
        req.setValue("id-token");
        service().loginOrRegisterGoogle(req);

        verify(userRepository, never()).findByOauthSubject(anyString());
        assertNull(user.getOauthSubject());
    }

    private User savedUser() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        return captor.getValue();
    }
}
