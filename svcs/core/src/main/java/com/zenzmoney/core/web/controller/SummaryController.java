package com.zenzmoney.core.web.controller;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.core.service.MonthlySummaryService;
import com.zenzmoney.core.web.dto.MonthlySummaryResponse;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dashboard's numbers (F-1.2 / F-1.17). This is what replaced reading a balance
 * off the account.
 */
@RestController
@RequestMapping("/api/v1/summary")
@RolesAllowed({"USER", "ADMIN"})
public class SummaryController {

    private final MonthlySummaryService monthlySummaryService;

    public SummaryController(MonthlySummaryService monthlySummaryService) {
        this.monthlySummaryService = monthlySummaryService;
    }

    /**
     * Income, expenses, and position for one calendar month.
     *
     * @param month ISO {@code yyyy-MM}; defaults to the caller's current month.
     */
    @GetMapping("/monthly")
    public ResponseEntity<ApiResponse<MonthlySummaryResponse>> monthly(
            @RequestParam(name = "month", required = false) String month) {
        return ResponseEntity.ok(ApiResponse.success(monthlySummaryService.summary(month)));
    }
}
