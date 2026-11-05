package org.example.artiselite.service;

import org.example.artiselite.dto.auth.AuthResponse;
import org.example.artiselite.dto.auth.LoginRequest;
import org.example.artiselite.dto.auth.RegisterRequest;
import org.example.artiselite.entity.User;
import org.example.artiselite.enums.Role;
import org.example.artiselite.repository.UserRepository;
import org.example.artiselite.security.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthService in isolation (repository/manager mocked). A real
 * BCryptPasswordEncoder is used - not mocked - so these tests catch the case
 * where a future change stores or compares plaintext passwords instead of hashes.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private CustomUserDetailsService userDetailsService;
    @Mock
    private AuditService auditService;

    // A real encoder (not mocked) so tests catch a future change that stops hashing passwords.
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtUtil,
                authenticationManager, userDetailsService, auditService);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, AuthorityUtils.createAuthorityList("ADMIN")));
    }

    @Test
    void register_elevatedRoleWithoutAdminCaller_isDeniedAndNeverSaves() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("sneaky");
        request.setEmail("sneaky@example.com");
        request.setPassword("password123");
        request.setFullName("Sneaky");
        request.setRole(Role.MANAGER);

        assertThrows(AccessDeniedException.class, () -> authService.register(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_hashesPasswordAndNeverStoresPlaintext() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("newuser");
        request.setEmail("newuser@example.com");
        request.setPassword("plaintextPassw0rd");
        request.setFullName("New User");
        request.setRole(Role.OPERATOR);

        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(userRepository.existsByEmail("newuser@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });

        UserDetails userDetails = org.springframework.security.core.userdetails.User
                .withUsername("newuser").password("hashed").authorities("OPERATOR").build();
        when(userDetailsService.loadUserByUsername("newuser")).thenReturn(userDetails);
        when(jwtUtil.generateToken(userDetails)).thenReturn("fake-jwt-token");

        authService.register(request);

        ArgumentCaptor<User> savedUserCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUserCaptor.capture());
        User savedUser = savedUserCaptor.getValue();

        assertThat(savedUser.getPassword()).isNotEqualTo("plaintextPassw0rd");
        assertThat(savedUser.getPassword()).startsWith("$2"); // BCrypt hash prefix
        assertThat(passwordEncoder.matches("plaintextPassw0rd", savedUser.getPassword())).isTrue();
    }

    @Test
    void register_responseReflectsSubmittedData_notAHardcodedUser() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("alice");
        request.setEmail("alice@example.com");
        request.setPassword("alicePassword1");
        request.setFullName("Alice Example");
        request.setRole(Role.MANAGER);
        loginAsAdmin(); // only an admin may create a MANAGER

        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(42L);
            return u;
        });

        UserDetails userDetails = org.springframework.security.core.userdetails.User
                .withUsername("alice").password("hashed").authorities("MANAGER").build();
        when(userDetailsService.loadUserByUsername("alice")).thenReturn(userDetails);
        when(jwtUtil.generateToken(userDetails)).thenReturn("token-for-alice");

        AuthResponse response = authService.register(request);

        assertThat(response.getUsername()).isEqualTo("alice");
        assertThat(response.getEmail()).isEqualTo("alice@example.com");
        assertThat(response.getFullName()).isEqualTo("Alice Example");
        assertThat(response.getRole()).isEqualTo(Role.MANAGER);
        assertThat(response.getToken()).isEqualTo("token-for-alice");
        assertThat(response.getUsername()).isNotEqualTo("recruiter@demo.com");
    }

    @Test
    void register_duplicateUsername_throwsAndNeverSaves() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("existing");
        request.setEmail("existing@example.com");
        request.setPassword("password123");
        request.setFullName("Existing User");

        when(userRepository.existsByUsername("existing")).thenReturn(true);

        assertThrows(RuntimeException.class, () -> authService.register(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void login_delegatesCredentialCheckToAuthenticationManager_andReturnsRealUserData() {
        LoginRequest request = new LoginRequest();
        request.setUsername("bob");
        request.setPassword("bobsPassword1");

        User bob = User.builder()
                .username("bob")
                .email("bob@example.com")
                .password(passwordEncoder.encode("bobsPassword1"))
                .fullName("Bob Builder")
                .role(Role.OPERATOR)
                .isActive(true)
                .build();
        bob.setId(7L); // id lives on the @MappedSuperclass, not covered by @Builder

        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(bob));

        UserDetails userDetails = org.springframework.security.core.userdetails.User
                .withUsername("bob").password(bob.getPassword()).authorities("OPERATOR").build();
        when(userDetailsService.loadUserByUsername("bob")).thenReturn(userDetails);
        when(jwtUtil.generateToken(userDetails)).thenReturn("token-for-bob");

        AuthResponse response = authService.login(request);

        ArgumentCaptor<UsernamePasswordAuthenticationToken> authCaptor =
                ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);
        verify(authenticationManager).authenticate(authCaptor.capture());
        assertThat(authCaptor.getValue().getPrincipal()).isEqualTo("bob");
        assertThat(authCaptor.getValue().getCredentials()).isEqualTo("bobsPassword1");

        assertThat(response.getUsername()).isEqualTo("bob");
        assertThat(response.getEmail()).isEqualTo("bob@example.com");
        assertThat(response.getRole()).isEqualTo(Role.OPERATOR);
        assertThat(response.getToken()).isEqualTo("token-for-bob");
        assertThat(response.getUsername()).isNotEqualTo("recruiter@demo.com");
    }

    @Test
    void login_invalidCredentials_propagatesAuthenticationFailure_andNeverIssuesToken() {
        LoginRequest request = new LoginRequest();
        request.setUsername("bob");
        request.setPassword("wrongPassword");

        doThrow(new BadCredentialsException("Bad credentials"))
                .when(authenticationManager).authenticate(any());

        assertThrows(BadCredentialsException.class, () -> authService.login(request));

        verify(jwtUtil, never()).generateToken(any());
        verify(userRepository, never()).findByUsername(anyString());
    }
}
