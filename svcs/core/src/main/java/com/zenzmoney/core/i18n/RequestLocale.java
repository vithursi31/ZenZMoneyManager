package com.zenzmoney.core.i18n;

import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.util.SupportedLanguages;
import com.zenzmoney.core.web.util.AuthUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * The language to answer this caller in.
 *
 * <p>Precedence is the stored preference first, {@code Accept-Language} second. The picker in
 * onboarding is a promise: a user who chose Sinhala on an English phone told us which they meant,
 * and letting the header win makes that setting look broken. The header is what we have when there
 * is no user yet — every public endpoint, and the rejected-token path.
 *
 * <p>Called from the error path only, so the lookup costs nothing on a request that succeeds, and
 * is always fresh. It must never throw: an exception raised while rendering an exception loses the
 * original failure.
 */
@Service
public class RequestLocale {

    private static final Logger log = LoggerFactory.getLogger(RequestLocale.class);

    private final UserRepository userRepository;
    private final SupportedLanguages supportedLanguages;

    public RequestLocale(UserRepository userRepository, SupportedLanguages supportedLanguages) {
        this.userRepository = userRepository;
        this.supportedLanguages = supportedLanguages;
    }

    public Locale resolve() {
        Locale stored = storedPreference();
        return stored != null ? stored : LocaleContextHolder.getLocale();
    }

    private Locale storedPreference() {
        try {
            if (!AuthUtil.isAuthenticated()) {
                return null;
            }
            Optional<User> user = userRepository.findByEmail(AuthUtil.currentUsername());
            return user.map(u -> supportedLanguages.match(u.getLanguage())).orElse(null);
        } catch (RuntimeException e) {
            // Falling back to the header is always a valid answer; failing here is not.
            log.warn("Could not read the caller's language preference, using the request locale: {}",
                    e.getMessage());
            return null;
        }
    }
}
