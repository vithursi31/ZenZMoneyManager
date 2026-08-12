package com.zenzmoney.core.web.controller;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.core.service.AccountService;
import com.zenzmoney.core.web.dto.AccountResponse;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only, and singular by design: a user has exactly one account, created for
 * them at onboarding (§1.4, F-1.1). There is no create, update, archive, delete,
 * or list — and no {@code /{id}} route, since the only account a caller may read
 * is their own.
 */
@RestController
@RequestMapping("/api/v1/account")
@RolesAllowed({"USER", "ADMIN"})
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<AccountResponse>> current() {
        return ResponseEntity.ok(ApiResponse.success(accountService.current()));
    }
}
