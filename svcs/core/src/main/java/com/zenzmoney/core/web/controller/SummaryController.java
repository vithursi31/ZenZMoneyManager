package com.zenzmoney.core.web.controller;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.core.service.CategoryBreakdownService;
import com.zenzmoney.core.service.MonthlySummaryService;
import com.zenzmoney.core.web.dto.CategoryBreakdownResponse;
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
    private final CategoryBreakdownService categoryBreakdownService;

    public SummaryController(MonthlySummaryService monthlySummaryService,
                             CategoryBreakdownService categoryBreakdownService) {
        this.monthlySummaryService = monthlySummaryService;
        this.categoryBreakdownService = categoryBreakdownService;
    }

    /**
     * Income, expenses, and position for one calendar month.
     *
     * @param month     ISO {@code yyyy-MM}; defaults to the caller's current month.
     * @param accountId optional; omit to span every account the caller holds.
     */
    @GetMapping("/monthly")
    public ResponseEntity<ApiResponse<MonthlySummaryResponse>> monthly(
            @RequestParam(name = "month", required = false) String month,
            @RequestParam(name = "accountId", required = false) String accountId) {
        return ResponseEntity.ok(ApiResponse.success(monthlySummaryService.summary(month, accountId)));
    }

    /**
     * Income and expenses over a period, split by category (F-1.19). Both dates are
     * required and inclusive; a calendar month is simply its first and last day.
     *
     * @param accountId optional; omit to span every account the caller holds.
     */
    @GetMapping("/breakdown")
    public ResponseEntity<ApiResponse<CategoryBreakdownResponse>> breakdown(
            @RequestParam(name = "startDate", required = false) String startDate,
            @RequestParam(name = "endDate", required = false) String endDate,
            @RequestParam(name = "accountId", required = false) String accountId) {
        return ResponseEntity.ok(ApiResponse.success(
                categoryBreakdownService.breakdown(startDate, endDate, accountId)));
    }
}
