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
     * List the caller's transactions, newest first — optionally filtered by
     * account, type, and/or a date range, in any combination. A user may hold
     * more than one account (F-1.1); omit {@code accountId} to span all of them.
     *
     * @param startDate ISO {@code yyyy-MM-dd}, inclusive, in the caller's timezone
     * @param endDate   ISO {@code yyyy-MM-dd}, inclusive, in the caller's timezone
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<TransactionResponse>>> list(
            @RequestParam(name = "accountId", required = false) String accountId,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "startDate", required = false) String startDate,
            @RequestParam(name = "endDate", required = false) String endDate) {
        return ResponseEntity.ok(
                ApiResponse.success(transactionService.list(accountId, type, startDate, endDate)));
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
