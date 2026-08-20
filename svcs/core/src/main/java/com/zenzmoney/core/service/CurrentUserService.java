package com.zenzmoney.core.service;

import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.common.status.ServiceCodes;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.web.util.AuthUtil;
import org.springframework.stereotype.Service;

/**
 * Resolves the authenticated caller to their {@link User} row. The JWT subject is
 * the user's <em>email</em>; every user-owned finance query scopes by the user's
 * <em>id</em> (§1.12), so services resolve the id through here rather than
 * duplicating the email→user lookup.
 */
@Service
public class CurrentUserService {

    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** The authenticated user, or throws 401 if anonymous / not found. */
    public User requireUser() {
        String email = AuthUtil.currentUsername();
        if (email == null || AuthUtil.ANONYMOUS.equals(email)) {
            throw new UnauthorizedException(ServiceCodes.SC_AUTHORIZATION_MISSING);
        }
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UnauthorizedException(ServiceCodes.SC_ACCOUNT_NOT_FOUND));
    }

    /** The authenticated user's id — the value every owned-entity query is scoped by. */
    public String requireUserId() {
        return requireUser().getId();
    }
}
