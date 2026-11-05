package org.example.artiselite.service;

import org.example.artiselite.dto.auth.LoginRequest;
import org.example.artiselite.dto.auth.RegisterRequest;
import org.example.artiselite.dto.auth.AuthResponse;
import org.example.artiselite.enums.Role;
import org.example.artiselite.entity.User;
import org.example.artiselite.exception.ConflictException;
import org.example.artiselite.repository.UserRepository;
import org.example.artiselite.security.*;


import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;
    private final CustomUserDetailsService userDetailsService;
    private final AuditService auditService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        Role role = request.getRole() != null ? request.getRole() : Role.OPERATOR;

        // /register is public, so only a logged-in admin may create anything above OPERATOR
        if (role != Role.OPERATOR && !isCurrentUserAdmin()) {
            throw new AccessDeniedException("Only an admin can create " + role + " accounts");
        }

        if (userRepository.existsByUsername(request.getUsername())) {
            throw new ConflictException("Username already exists");
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ConflictException("Email already exists");
        }

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .role(role)
                .phoneNumber(request.getPhoneNumber())
                .isActive(true)
                .permissions(getDefaultPermissions(role))
                .build();

        user = userRepository.save(user);

        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
        String token = jwtUtil.generateToken(userDetails);

        return AuthResponse.builder()
                .token(token)
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole())
                .permissions(user.getPermissions())
                .build();
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsername(),
                        request.getPassword()
                )
        );

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
        String token = jwtUtil.generateToken(userDetails);

        auditService.logAction(user.getId(), "LOGIN", "User", user.getId(),
                "User logged in", null);

        return AuthResponse.builder()
                .token(token)
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole())
                .permissions(user.getPermissions())
                .build();
    }

    private boolean isCurrentUserAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> Role.ADMIN.name().equals(a.getAuthority()));
    }

    private Set<String> getDefaultPermissions(Role role) {
        Set<String> permissions = new HashSet<>();

        if (role == null || role == Role.OPERATOR) {
            permissions.add("INVENTORY_READ");
            permissions.add("INBOUND_READ");
            permissions.add("INBOUND_WRITE");
            permissions.add("OUTBOUND_READ");
            permissions.add("OUTBOUND_WRITE");
            permissions.add("DASHBOARD_VIEW");
        } else if (role == Role.MANAGER) {
            permissions.add("INVENTORY_READ");
            permissions.add("INVENTORY_WRITE");
            permissions.add("INBOUND_READ");
            permissions.add("INBOUND_WRITE");
            permissions.add("OUTBOUND_READ");
            permissions.add("OUTBOUND_WRITE");
            permissions.add("SUPPLIER_READ");
            permissions.add("SUPPLIER_WRITE");
            permissions.add("AUDIT_READ");
            permissions.add("DASHBOARD_VIEW");
        } else if (role == Role.ADMIN) {
            permissions.add("INVENTORY_READ");
            permissions.add("INVENTORY_WRITE");
            permissions.add("INVENTORY_DELETE");
            permissions.add("INBOUND_READ");
            permissions.add("INBOUND_WRITE");
            permissions.add("INBOUND_DELETE");
            permissions.add("OUTBOUND_READ");
            permissions.add("OUTBOUND_WRITE");
            permissions.add("OUTBOUND_DELETE");
            permissions.add("USER_READ");
            permissions.add("USER_WRITE");
            permissions.add("USER_DELETE");
            permissions.add("SUPPLIER_READ");
            permissions.add("SUPPLIER_WRITE");
            permissions.add("SUPPLIER_DELETE");
            permissions.add("AUDIT_READ");
            permissions.add("DASHBOARD_VIEW");
            permissions.add("SETTINGS_MANAGE");
        }

        return permissions;
    }
}