package org.example.artiselite.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.artiselite.dto.auth.LoginRequest;
import org.example.artiselite.dto.auth.RegisterRequest;
import org.example.artiselite.entity.User;
import org.example.artiselite.enums.Role;
import org.example.artiselite.repository.UserRepository;
import org.example.artiselite.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end tests for the real authentication flow (register/login), hitting the
 * actual AuthController -> AuthService -> UserRepository -> H2 test database.
 *
 * These specifically guard against the historical bug where /login and /register
 * ignored the request body and always returned a hardcoded recruiter@demo.com user
 * without ever touching the database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthControllerIntegrationTest {

    private static final String HARDCODED_FAKE_EMAIL = "recruiter@demo.com";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtUtil jwtUtil;

    private RegisterRequest registerRequest(String username, String email, String password, Role role) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setEmail(email);
        request.setPassword(password);
        request.setFullName("Test User " + username);
        request.setRole(role);
        return request;
    }

    @Test
    void register_persistsRealUserInDatabase_andResponseReflectsSubmittedData() throws Exception {
        RegisterRequest request = registerRequest("newbie", "newbie@example.com", "password123", Role.OPERATOR);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("newbie"))
                .andExpect(jsonPath("$.email").value("newbie@example.com"))
                .andExpect(jsonPath("$.fullName").value("Test User newbie"))
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andExpect(jsonPath("$.email").value(not(HARDCODED_FAKE_EMAIL)))
                .andExpect(jsonPath("$.token").isNotEmpty());

        // The row must actually exist in the database, not just in the HTTP response.
        User saved = userRepository.findByUsername("newbie").orElseThrow(
                () -> new AssertionError("register() did not persist a User row for 'newbie'"));
        assertThat(saved.getEmail()).isEqualTo("newbie@example.com");
        assertThat(saved.getFullName()).isEqualTo("Test User newbie");
        assertThat(saved.getRole()).isEqualTo(Role.OPERATOR);
        // Password must be hashed, never stored as submitted plaintext.
        assertThat(saved.getPassword()).isNotEqualTo("password123");
        assertThat(passwordEncoder.matches("password123", saved.getPassword())).isTrue();
    }

    @Test
    void register_tokenSubjectMatchesRegisteredUsername_notAFakeUser() throws Exception {
        RegisterRequest request = registerRequest("carol", "carol@example.com", "password123", Role.OPERATOR);

        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String token = objectMapper.readTree(body).get("token").asText();
        assertThat(jwtUtil.extractUsername(token)).isEqualTo("carol");
        assertThat(jwtUtil.extractUsername(token)).isNotEqualTo("recruiter");
    }

    @Test
    void login_withValidCredentials_returnsWorkingTokenForTheRealUser() throws Exception {
        RegisterRequest reg = registerRequest("dave", "dave@example.com", "correctPassword1", Role.OPERATOR);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isOk());

        LoginRequest login = new LoginRequest();
        login.setUsername("dave");
        login.setPassword("correctPassword1");

        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("dave"))
                .andExpect(jsonPath("$.email").value("dave@example.com"))
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andExpect(jsonPath("$.email").value(not(HARDCODED_FAKE_EMAIL)))
                .andReturn().getResponse().getContentAsString();

        String token = objectMapper.readTree(body).get("token").asText();
        assertThat(jwtUtil.extractUsername(token)).isEqualTo("dave");
    }

    @Test
    void login_withWrongPassword_isRejected_andNeverReturnsAToken() throws Exception {
        RegisterRequest reg = registerRequest("erin", "erin@example.com", "correctPassword1", Role.OPERATOR);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isOk());

        LoginRequest badLogin = new LoginRequest();
        badLogin.setUsername("erin");
        badLogin.setPassword("totallyWrongPassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badLogin)))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void login_withNonexistentUser_isRejected() throws Exception {
        LoginRequest login = new LoginRequest();
        login.setUsername("does-not-exist-" + System.nanoTime());
        login.setPassword("whatever123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void register_duplicateUsername_isRejected_andDoesNotOverwriteExistingUser() throws Exception {
        RegisterRequest first = registerRequest("frank", "frank@example.com", "password123", Role.OPERATOR);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isOk());

        RegisterRequest duplicate = registerRequest("frank", "different-email@example.com", "otherPassword1", Role.OPERATOR);

        // Duplicate usernames are a ConflictException, mapped to HTTP 409 via @ResponseStatus.
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicate)))
                .andExpect(status().isConflict());

        // Original user must be untouched.
        User stillOriginal = userRepository.findByUsername("frank").orElseThrow();
        assertThat(stillOriginal.getEmail()).isEqualTo("frank@example.com");
        assertThat(stillOriginal.getRole()).isEqualTo(Role.OPERATOR);
    }

    @Test
    void register_anonymousCannotSelfAssignAdmin_andNoUserIsCreated() throws Exception {
        RegisterRequest request = registerRequest("mallory", "mallory@example.com", "password123", Role.ADMIN);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findByUsername("mallory")).isEmpty();
    }

    @Test
    void register_withoutRole_defaultsToOperator() throws Exception {
        RegisterRequest request = registerRequest("norole", "norole@example.com", "password123", null);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OPERATOR"));
    }

    @Test
    void register_loggedInAdminCanCreateManager() throws Exception {
        String adminBody = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                registerRequest("root", "root@example.com", "password123", Role.OPERATOR))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // Promote directly in the DB: the role is re-read on every request, so the same token now acts as ADMIN.
        User admin = userRepository.findByUsername("root").orElseThrow();
        admin.setRole(Role.ADMIN);
        userRepository.save(admin);
        String adminToken = objectMapper.readTree(adminBody).get("token").asText();

        mockMvc.perform(post("/api/auth/register")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                registerRequest("newmgr", "newmgr@example.com", "password123", Role.MANAGER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MANAGER"));
    }
}
