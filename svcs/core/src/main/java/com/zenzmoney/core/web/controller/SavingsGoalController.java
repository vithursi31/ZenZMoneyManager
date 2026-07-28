package com.zenzmoney.core.web.controller;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.core.service.SavingsGoalService;
import com.zenzmoney.core.web.dto.ContributionResponse;
import com.zenzmoney.core.web.dto.CreateContributionRequest;
import com.zenzmoney.core.web.dto.CreateGoalRequest;
import com.zenzmoney.core.web.dto.GoalResponse;
import com.zenzmoney.core.web.dto.UpdateGoalRequest;
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
@RequestMapping("/api/v1/goals")
@RolesAllowed({"USER", "ADMIN"})
public class SavingsGoalController {

    private final SavingsGoalService goalService;

    public SavingsGoalController(SavingsGoalService goalService) {
        this.goalService = goalService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<GoalResponse>> create(@Valid @RequestBody CreateGoalRequest req) {
        return ResponseEntity.ok(ApiResponse.success(goalService.create(req)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<GoalResponse>>> list(
            @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return ResponseEntity.ok(ApiResponse.success(goalService.list(includeArchived)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<GoalResponse>> get(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(goalService.get(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<GoalResponse>> update(@PathVariable String id,
                                                            @Valid @RequestBody UpdateGoalRequest req) {
        return ResponseEntity.ok(ApiResponse.success(goalService.update(id, req)));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<ApiResponse<GoalResponse>> archive(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(goalService.archive(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, String>>> delete(@PathVariable String id) {
        goalService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", "Goal deleted")));
    }

    // --- contributions ---

    @PostMapping("/{id}/contributions")
    public ResponseEntity<ApiResponse<ContributionResponse>> addContribution(
            @PathVariable String id, @Valid @RequestBody CreateContributionRequest req) {
        return ResponseEntity.ok(ApiResponse.success(goalService.addContribution(id, req)));
    }

    @GetMapping("/{id}/contributions")
    public ResponseEntity<ApiResponse<List<ContributionResponse>>> listContributions(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(goalService.listContributions(id)));
    }

    @DeleteMapping("/{id}/contributions/{contributionId}")
    public ResponseEntity<ApiResponse<Map<String, String>>> deleteContribution(
            @PathVariable String id, @PathVariable String contributionId) {
        goalService.deleteContribution(id, contributionId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", "Contribution deleted")));
    }
}
