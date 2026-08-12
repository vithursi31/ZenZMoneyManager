package com.zenzmoney.core.web.controller;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.core.service.TransactionService;
import com.zenzmoney.core.web.dto.CreateTransactionRequest;
import com.zenzmoney.core.web.dto.TransactionResponse;
import com.zenzmoney.core.web.dto.UpdateTransactionRequest;
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
@RequestMapping("/api/v1/transactions")
@RolesAllowed({"USER", "ADMIN"})
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TransactionResponse>> create(@Valid @RequestBody CreateTransactionRequest req) {
        return ResponseEntity.ok(ApiResponse.success(transactionService.create(req)));
    }

    /**
     * List the caller's transactions, optionally within a date range (epoch millis).
     * There is no account filter — the caller has one account (§1.4).
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<TransactionResponse>>> list(
            @RequestParam(name = "from", required = false) Long from,
            @RequestParam(name = "to", required = false) Long to) {
        return ResponseEntity.ok(ApiResponse.success(transactionService.list(from, to)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TransactionResponse>> get(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(transactionService.get(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TransactionResponse>> update(@PathVariable String id,
                                                                   @Valid @RequestBody UpdateTransactionRequest req) {
        return ResponseEntity.ok(ApiResponse.success(transactionService.update(id, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, String>>> delete(@PathVariable String id) {
        transactionService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", "Transaction deleted")));
    }
}
