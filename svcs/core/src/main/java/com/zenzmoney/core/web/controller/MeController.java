package com.zenzmoney.core.web.controller;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.core.service.ChangePasswordService;
import com.zenzmoney.core.service.CurrentUserService;
import com.zenzmoney.core.service.ProfileService;
import com.zenzmoney.core.web.dto.ChangePasswordRequest;
import com.zenzmoney.core.web.dto.MeResponse;
import com.zenzmoney.core.web.dto.UpdateProfileRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class MeController {

    private final CurrentUserService currentUser;
    private final ProfileService profileService;
    private final ChangePasswordService changePasswordService;

    public MeController(CurrentUserService currentUser,
                        ProfileService profileService,
                        ChangePasswordService changePasswordService) {
        this.currentUser = currentUser;
        this.profileService = profileService;
        this.changePasswordService = changePasswordService;
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

    @PutMapping("/me")
    @RolesAllowed({"USER", "ADMIN"})
    public ResponseEntity<ApiResponse<MeResponse>> updateProfile(@Valid @RequestBody UpdateProfileRequest req) {
        return ResponseEntity.ok(ApiResponse.success(profileService.updateProfile(req)));
    }

    @PostMapping("/change-password")
    @RolesAllowed({"USER", "ADMIN"})
    public ResponseEntity<ApiResponse<Map<String, String>>> changePassword(
            @Valid @RequestBody ChangePasswordRequest req) {
        changePasswordService.changePassword(req.getCurrentPassword(), req.getNewPassword());
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", "Password changed")));
    }

    @GetMapping("/admin/ping")
    @RolesAllowed("ADMIN")
    public ResponseEntity<ApiResponse<Map<String, String>>> adminPing() {
        return ResponseEntity.ok(ApiResponse.success(Map.of("status", "ok")));
    }
}
