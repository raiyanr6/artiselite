package org.example.artiselite.controller;

import org.example.artiselite.dto.auth.*;
import org.example.artiselite.security.JwtUtil;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtUtil jwtUtil;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
        return handleBypassAuth("recruiter@demo.com");
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        return handleBypassAuth("recruiter@demo.com");
    }

    private ResponseEntity<AuthResponse> handleBypassAuth(String email) {
        // Build a dummy user with ADMIN authority
        UserDetails dummyUser = User.builder()
                .username(email)
                .password("dummy")
                .authorities("ADMIN")
                .build();

        String token = jwtUtil.generateToken(dummyUser);

        // Uses standard constructor fallback to avoid builder mismatch issues
        AuthResponse response = new AuthResponse();
        response.setToken(token);

        return ResponseEntity.ok(response);
    }
}