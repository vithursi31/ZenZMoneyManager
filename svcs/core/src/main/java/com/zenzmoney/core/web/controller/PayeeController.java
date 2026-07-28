package com.zenzmoney.core.web.controller;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.core.service.PayeeService;
import com.zenzmoney.core.web.dto.CreatePayeeRequest;
import com.zenzmoney.core.web.dto.PayeeResponse;
import com.zenzmoney.core.web.dto.UpdatePayeeRequest;
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
@RequestMapping("/api/v1/payees")
@RolesAllowed({"USER", "ADMIN"})
public class PayeeController {

    private final PayeeService payeeService;

    public PayeeController(PayeeService payeeService) {
        this.payeeService = payeeService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PayeeResponse>> create(@Valid @RequestBody CreatePayeeRequest req) {
        return ResponseEntity.ok(ApiResponse.success(payeeService.create(req)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<PayeeResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success(payeeService.list()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PayeeResponse>> get(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(payeeService.get(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<PayeeResponse>> update(@PathVariable String id,
                                                             @Valid @RequestBody UpdatePayeeRequest req) {
        return ResponseEntity.ok(ApiResponse.success(payeeService.update(id, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, String>>> delete(@PathVariable String id) {
        payeeService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", "Payee deleted")));
    }
}
