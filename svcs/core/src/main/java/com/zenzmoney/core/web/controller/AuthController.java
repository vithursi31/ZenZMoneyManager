package com.zenzmoney.core.web.controller;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.core.service.JwtTokenService;
import com.zenzmoney.core.service.LoginService;
import com.zenzmoney.core.service.OAuthLoginService;
import com.zenzmoney.core.service.PasswordResetService;
import com.zenzmoney.core.service.RegistrationService;
import com.zenzmoney.core.web.dto.AppleAuthRequest;
import com.zenzmoney.core.web.dto.AuthenticationRequest;
import com.zenzmoney.core.web.dto.AuthenticationResponse;
import com.zenzmoney.core.web.dto.FacebookAuthRequest;
import com.zenzmoney.core.web.dto.ForgotPasswordRequest;
import com.zenzmoney.core.web.dto.GoogleAuthRequest;
import com.zenzmoney.core.web.dto.RegisterRequest;
import com.zenzmoney.core.web.dto.RegisterResponse;
import com.zenzmoney.core.web.dto.ResetPasswordRequest;
import io.jsonwebtoken.Claims;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final LoginService loginService;
    private final RegistrationService registrationService;
    private final OAuthLoginService oauthLoginService;
    private final PasswordResetService passwordResetService;
    private final JwtTokenService jwtTokenService;

    public AuthController(LoginService loginService,
                          RegistrationService registrationService,
                          OAuthLoginService oauthLoginService,
                          PasswordResetService passwordResetService,
                          JwtTokenService jwtTokenService) {
        this.loginService = loginService;
        this.registrationService = registrationService;
        this.oauthLoginService = oauthLoginService;
        this.passwordResetService = passwordResetService;
        this.jwtTokenService = jwtTokenService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(@RequestBody RegisterRequest req) {
        return ResponseEntity.ok(ApiResponse.success(registrationService.register(req)));
    }

    @GetMapping("/verify-email")
    public ResponseEntity<ApiResponse<AuthenticationResponse>> verifyEmail(@RequestParam("token") String token) {
        return ResponseEntity.ok(ApiResponse.success(registrationService.verifyEmail(token)));
    }

    @PostMapping("/authenticate")
    public ResponseEntity<ApiResponse<AuthenticationResponse>> authenticate(@RequestBody AuthenticationRequest req) {
        return ResponseEntity.ok(ApiResponse.success(
                loginService.login(req.getEmail(), req.getPassword())));
    }

    @PostMapping("/authenticate/google")
    public ResponseEntity<ApiResponse<AuthenticationResponse>> google(@RequestBody GoogleAuthRequest req) {
        return ResponseEntity.ok(ApiResponse.success(oauthLoginService.loginOrRegisterGoogle(req)));
    }

    @PostMapping("/authenticate/apple")
    public ResponseEntity<ApiResponse<AuthenticationResponse>> apple(@RequestBody AppleAuthRequest req) {
        return ResponseEntity.ok(ApiResponse.success(oauthLoginService.loginOrRegisterApple(req)));
    }

    @PostMapping("/authenticate/facebook")
    public ResponseEntity<ApiResponse<AuthenticationResponse>> facebook(@RequestBody FacebookAuthRequest req) {
        return ResponseEntity.ok(ApiResponse.success(oauthLoginService.loginOrRegisterFacebook(req)));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Map<String, String>>> forgotPassword(
            @RequestBody ForgotPasswordRequest req) {
        passwordResetService.forgotPassword(req.getEmail());
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "message", "If an account exists for that email, a reset link has been sent.")));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<AuthenticationResponse>> resetPassword(
            @RequestBody ResetPasswordRequest req) {
        return ResponseEntity.ok(ApiResponse.success(
                passwordResetService.resetPassword(req.getToken(), req.getNewPassword())));
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<ApiResponse<Map<String, String>>> refresh(
            @RequestHeader("Authorization") String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            throw new UnauthorizedException("NO_TOKEN", "Missing authorization header");
        }
        String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader.trim();

        Claims claims = jwtTokenService.extractClaims(token);
        String type = jwtTokenService.extractTokenType(claims);
        if (!JwtTokenService.TYPE_REFRESH.equals(type)) {
            throw new UnauthorizedException("INVALID_TOKEN",
                    "Required refresh token but found '" + type + "'");
        }

        String accessToken = jwtTokenService.generateAccessToken(claims.getSubject());
        return ResponseEntity.ok(ApiResponse.success(Map.of("accessToken", accessToken)));
    }
}
