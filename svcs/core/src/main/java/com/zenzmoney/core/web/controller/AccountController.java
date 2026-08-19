package com.zenzmoney.core.web.controller;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.core.service.AccountService;
import com.zenzmoney.core.web.dto.AccountResponse;
import com.zenzmoney.core.web.dto.CreateAccountRequest;
import com.zenzmoney.core.web.dto.UpdateAccountNameRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

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

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<AccountResponse>>> listActive() {
        return ResponseEntity.ok(ApiResponse.success(accountService.listActive()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AccountResponse>> findOne(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(accountService.findOne(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AccountResponse>> create(@Valid @RequestBody CreateAccountRequest req) {
        return ResponseEntity.ok(ApiResponse.success(accountService.create(req)));
    }

    @PutMapping("/{id}/name")
    public ResponseEntity<ApiResponse<AccountResponse>> rename(@PathVariable String id,
                                                                @Valid @RequestBody UpdateAccountNameRequest req) {
        return ResponseEntity.ok(ApiResponse.success(accountService.updateName(id, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, String>>> delete(@PathVariable String id) {
        accountService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", "Account deleted")));
    }
}
