package com.zenzmoney.core.web.controller;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.core.service.BudgetService;
import com.zenzmoney.core.web.dto.BudgetResponse;
import com.zenzmoney.core.web.dto.BudgetSummaryResponse;
import com.zenzmoney.core.web.dto.CreateBudgetRequest;
import com.zenzmoney.core.web.dto.UpdateBudgetRequest;
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
@RequestMapping("/api/v1/budgets")
@RolesAllowed({"USER", "ADMIN"})
public class BudgetController {

    private final BudgetService budgetService;

    public BudgetController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BudgetResponse>> create(@Valid @RequestBody CreateBudgetRequest req) {
        return ResponseEntity.ok(ApiResponse.success(budgetService.create(req)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<BudgetResponse>>> list(
            @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return ResponseEntity.ok(ApiResponse.success(budgetService.list(includeArchived)));
    }

    /**
     * One month's plan against its outcome: the caps set for {@code month} (ISO
     * {@code yyyy-MM}, defaulting to the caller's current month) and the spend
     * against them so far.
     */
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<BudgetSummaryResponse>> summary(
            @RequestParam(name = "month", required = false) String month) {
        return ResponseEntity.ok(ApiResponse.success(budgetService.summary(month)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<BudgetResponse>> get(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(budgetService.get(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<BudgetResponse>> update(@PathVariable String id,
                                                              @Valid @RequestBody UpdateBudgetRequest req) {
        return ResponseEntity.ok(ApiResponse.success(budgetService.update(id, req)));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<ApiResponse<BudgetResponse>> archive(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(budgetService.archive(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, String>>> delete(@PathVariable String id) {
        budgetService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", "Budget deleted")));
    }
}
