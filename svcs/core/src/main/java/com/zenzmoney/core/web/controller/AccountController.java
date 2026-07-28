package com.zenzmoney.core.web.controller;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.core.service.AccountService;
import com.zenzmoney.core.web.dto.AccountResponse;
import com.zenzmoney.core.web.dto.CreateAccountRequest;
import com.zenzmoney.core.web.dto.UpdateAccountRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/accounts")
@RolesAllowed({"USER", "ADMIN"})
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AccountResponse>> create(@Valid @RequestBody CreateAccountRequest req) {
        return ResponseEntity.ok(ApiResponse.success(accountService.create(req)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<AccountResponse>>> list(
            @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return ResponseEntity.ok(ApiResponse.success(accountService.list(includeArchived)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AccountResponse>> get(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(accountService.get(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<AccountResponse>> update(@PathVariable String id,
                                                               @Valid @RequestBody UpdateAccountRequest req) {
        return ResponseEntity.ok(ApiResponse.success(accountService.update(id, req)));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<ApiResponse<AccountResponse>> archive(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(accountService.archive(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, String>>> delete(@PathVariable String id) {
        accountService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", "Account deleted")));
    }
}
