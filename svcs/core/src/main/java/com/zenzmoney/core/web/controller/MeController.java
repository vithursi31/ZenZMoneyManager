package com.zenzmoney.core.web.controller;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.core.service.CurrentUserService;
import com.zenzmoney.core.web.dto.MeResponse;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class MeController {

    private final CurrentUserService currentUser;

    public MeController(CurrentUserService currentUser) {
        this.currentUser = currentUser;
    }

    /**
     * Resolves the user row rather than reading the token alone, because the answer
     * a client needs after login is not only who they are but whether they have
     * onboarded (F-1.27).
     */
    @GetMapping("/me")
    @RolesAllowed({"USER", "ADMIN"})
    public ResponseEntity<ApiResponse<MeResponse>> me() {
        return ResponseEntity.ok(ApiResponse.success(MeResponse.of(currentUser.requireUser())));
    }

    @GetMapping("/admin/ping")
    @RolesAllowed("ADMIN")
    public ResponseEntity<ApiResponse<Map<String, String>>> adminPing() {
        return ResponseEntity.ok(ApiResponse.success(Map.of("status", "ok")));
    }
}
