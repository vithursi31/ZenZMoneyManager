package com.zenzmoney.core.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.io.IOException;

/**
 * Seeds {@link LocaleContextHolder} from {@code Accept-Language} at the edge of the security chain.
 *
 * <p>DispatcherServlet already does this for anything that reaches a controller. This filter exists
 * for what does not: {@link JwtAuthenticationFilter} writes 401 bodies and {@code SecurityConfig}'s
 * access-denied handler writes 403 bodies <em>before</em> the dispatcher runs, and those are exactly
 * the messages a user sees when the app stops working.
 *
 * <p>Header only — no database. The authenticated user's stored preference outranks the header but
 * costs a query, so it is resolved lazily on the error path in {@code RequestLocale}; a successful
 * request must not pay for a message it never renders.
 *
 * <p>Not a {@code @Component}, for the same reason as {@link MdcContextFilter}: Spring Boot
 * auto-registers a {@code Filter} bean ahead of the security chain, and {@link OncePerRequestFilter}
 * then suppresses the real pass.
 */
public class RequestLocaleFilter extends OncePerRequestFilter {

    private final AcceptHeaderLocaleResolver localeResolver;

    public RequestLocaleFilter(AcceptHeaderLocaleResolver localeResolver) {
        this.localeResolver = localeResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // SupportedLocaleResolver already answers with one of our locales, so there is nothing to
        // post-process here — and nothing that could disagree with what DispatcherServlet computes
        // later for the same request.
        LocaleContextHolder.setLocale(localeResolver.resolveLocale(request));
        try {
            chain.doFilter(request, response);
        } finally {
            // Worker threads are pooled: a locale left behind is served to the next caller.
            LocaleContextHolder.resetLocaleContext();
        }
    }
}
