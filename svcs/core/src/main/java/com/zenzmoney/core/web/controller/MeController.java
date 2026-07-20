package com.zenzmoney.core.web.controller;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.core.web.util.AuthUtil;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class MeController {

    @GetMapping("/me")
    @RolesAllowed({"USER", "ADMIN"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> me() {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "email", AuthUtil.currentUsername(),
                "authenticated", AuthUtil.isAuthenticated()
        )));
    }

    @GetMapping("/admin/ping")
    @RolesAllowed("ADMIN")
    public ResponseEntity<ApiResponse<Map<String, String>>> adminPing() {
        return ResponseEntity.ok(ApiResponse.success(Map.of("status", "ok")));
    }
}
