package com.habit.core.web.util;

import com.habit.common.domain.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

public final class AuthUtil {

    public static final String ANONYMOUS = "anonymous";

    private AuthUtil() {}

    public static Authentication current() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    public static String currentUsername() {
        Authentication auth = current();
        return auth == null ? ANONYMOUS : auth.getName();
    }

    public static boolean isAuthenticated() {
        Authentication auth = current();
        if (auth == null || !auth.isAuthenticated()) return false;
        return !ANONYMOUS.equals(auth.getName());
    }

    public static boolean isAnonymous() {
        return !isAuthenticated();
    }

    public static boolean hasRole(Role role) {
        Authentication auth = current();
        if (auth == null) return false;
        String target = "ROLE_" + role.name();
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (target.equals(ga.getAuthority())) return true;
        }
        return false;
    }
}
