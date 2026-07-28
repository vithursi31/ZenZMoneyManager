package com.zenzmoney.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.core.logging.AppLog;
import com.zenzmoney.core.web.util.AuthUtil;
import com.zenzmoney.core.web.filter.JwtAuthenticationFilter;
import com.zenzmoney.core.web.filter.MdcContextFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(jsr250Enabled = true)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final CorsConfigurationSource corsConfigurationSource;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter,
                          CorsConfigurationSource corsConfigurationSource) {
        this.jwtFilter = jwtFilter;
        this.corsConfigurationSource = corsConfigurationSource;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // After the JWT filter so the MDC "user" key is the resolved principal rather than
                // "anonymous". Constructed here, not injected: a Filter @Component would also be
                // auto-registered into the servlet chain ahead of security, and OncePerRequestFilter
                // would then suppress this one. See MdcContextFilter.
                .addFilterAfter(new MdcContextFilter(), JwtAuthenticationFilter.class)
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .anonymous(anon -> anon
                        .principal("anonymous")
                        .authorities("ROLE_ANONYMOUS"))
                .authorizeHttpRequests(auth -> auth
                        // URL-level rules are permissive; method-level @RolesAllowed enforces access.
                        // Webhooks must stay open by URL since they have no JWT.
                        .requestMatchers("/stripe/webhook").permitAll()
                        .anyRequest().permitAll()
                )
                .exceptionHandling(ex -> ex
                        .accessDeniedHandler(accessDeniedHandler()))
                .formLogin(form -> form.disable())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessUrl("/?logout=true"));

        return http.build();
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        ObjectMapper mapper = new ObjectMapper();
        return (request, response, ex) -> {
            String path = request.getRequestURI();
            // Denials that reach the filter chain rather than AccessDeniedAdvice. Audited in both
            // places on purpose: this is the fail-closed path, and a denial nobody recorded is a
            // denial nobody can explain afterwards.
            AppLog.AUDIT.warn("Access denied at the filter chain: {} {} for {}",
                    request.getMethod(), path, AuthUtil.currentUsername());
            if (path.startsWith("/api/")) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                mapper.writeValue(response.getOutputStream(),
                        ApiResponse.error("E1014", "Access denied"));
            } else {
                response.sendRedirect("/error/403");
            }
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
