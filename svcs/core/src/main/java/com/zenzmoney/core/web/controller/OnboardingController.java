package com.zenzmoney.core.web.controller;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.core.service.OnboardingService;
import com.zenzmoney.core.web.dto.CurrencyResponse;
import com.zenzmoney.core.web.dto.LanguageResponse;
import com.zenzmoney.core.web.dto.OnboardingRequest;
import com.zenzmoney.core.web.dto.OnboardingResponse;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * First-run setup (F-1.27). Authenticated, not public: onboarding configures the
 * caller's own account, so it runs after registration/verification rather than
 * being another anonymous endpoint.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@RolesAllowed({"USER", "ADMIN"})
public class OnboardingController {

    private final OnboardingService onboardingService;

    public OnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<OnboardingResponse>> complete(
            @Valid @RequestBody OnboardingRequest req) {
        return ResponseEntity.ok(ApiResponse.success(onboardingService.complete(req)));
    }

    /** The currency picker's options (F-1.27) — same ISO-4217 list {@link #complete} validates against. */
    @GetMapping("/currencies")
    public ResponseEntity<ApiResponse<List<CurrencyResponse>>> currencies() {
        return ResponseEntity.ok(ApiResponse.success(onboardingService.listCurrencies()));
    }

    /**
     * The language picker's options (F-1.26) — the same allowlist {@link #complete} and
     * {@code PUT /me} validate against, so a client never offers a language the server would refuse.
     */
    @GetMapping("/languages")
    public ResponseEntity<ApiResponse<List<LanguageResponse>>> languages() {
        return ResponseEntity.ok(ApiResponse.success(onboardingService.listLanguages()));
    }
}
