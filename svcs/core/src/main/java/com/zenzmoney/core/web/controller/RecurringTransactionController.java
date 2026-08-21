package com.zenzmoney.core.web.controller;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.core.service.RecurringTransactionService;
import com.zenzmoney.core.web.dto.CreateRecurringRequest;
import com.zenzmoney.core.web.dto.RecurringCreatedResponse;
import com.zenzmoney.core.web.dto.RecurringResponse;
import com.zenzmoney.core.web.dto.UpcomingOccurrenceResponse;
import com.zenzmoney.core.web.dto.UpdateRecurringRequest;
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
@RequestMapping("/api/v1/recurring")
@RolesAllowed({"USER", "ADMIN"})
public class RecurringTransactionController {

    private final RecurringTransactionService recurringService;

    public RecurringTransactionController(RecurringTransactionService recurringService) {
        this.recurringService = recurringService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<RecurringCreatedResponse>> create(
            @Valid @RequestBody CreateRecurringRequest req) {
        return ResponseEntity.ok(ApiResponse.success(recurringService.create(req)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<RecurringResponse>>> list(
            @RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {
        return ResponseEntity.ok(ApiResponse.success(recurringService.list(includeInactive)));
    }

    /**
     * Bills, renewals and salary falling due in the next {@code withinDays} days.
     * These are projections, not ledger rows — see {@code RecurringTransactionService#upcoming}.
     */
    @GetMapping("/upcoming")
    public ResponseEntity<ApiResponse<List<UpcomingOccurrenceResponse>>> upcoming(
            @RequestParam(name = "withinDays", required = false) Integer withinDays) {
        return ResponseEntity.ok(ApiResponse.success(recurringService.upcoming(withinDays)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<RecurringResponse>> get(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(recurringService.get(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<RecurringResponse>> update(@PathVariable String id,
                                                                 @Valid @RequestBody UpdateRecurringRequest req) {
        return ResponseEntity.ok(ApiResponse.success(recurringService.update(id, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, String>>> delete(@PathVariable String id) {
        recurringService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", "Recurring template deleted")));
    }
}
