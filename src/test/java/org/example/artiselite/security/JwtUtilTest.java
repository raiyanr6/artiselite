package org.example.artiselite.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plain unit tests for JwtUtil, with no Spring context. @Value fields are set
 * via reflection since this class is instantiated directly rather than injected.
 */
class JwtUtilTest {

    private static final String SECRET = "RXhhbXBsZVRlc3RTZWNyZXRLZXlGb3JKV1RTaWduaW5nVGVzdHMxMjM0NTY=";

    private JwtUtil jwtUtil;
    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expiration", 3_600_000L);

        userDetails = new User("jdoe", "irrelevant-hash", java.util.List.of());
    }

    @Test
    void generateToken_embedsUsernameAsSubject() {
        String token = jwtUtil.generateToken(userDetails);

        assertThat(token).isNotBlank();
        assertThat(jwtUtil.extractUsername(token)).isEqualTo("jdoe");
    }

    @Test
    void generateToken_setsExpirationInTheFuture() {
        String token = jwtUtil.generateToken(userDetails);

        assertThat(jwtUtil.extractExpiration(token)).isAfter(new java.util.Date());
    }

    @Test
    void validateToken_validTokenForMatchingUser_returnsTrue() {
        String token = jwtUtil.generateToken(userDetails);

        assertThat(jwtUtil.validateToken(token, userDetails)).isTrue();
    }

    @Test
    void validateToken_tokenForDifferentUser_returnsFalse() {
        String token = jwtUtil.generateToken(userDetails);
        UserDetails otherUser = new User("someone-else", "irrelevant-hash", java.util.List.of());

        assertThat(jwtUtil.validateToken(token, otherUser)).isFalse();
    }

    @Test
    void validateToken_expiredToken_throwsExpiredJwtException() {
        ReflectionTestUtils.setField(jwtUtil, "expiration", -1000L);
        String expiredToken = jwtUtil.generateToken(userDetails);

        assertThrows(ExpiredJwtException.class,
                () -> jwtUtil.validateToken(expiredToken, userDetails));
    }

    @Test
    void validateToken_tamperedSignature_throwsJwtException() {
        String token = jwtUtil.generateToken(userDetails);
        // Flip the last character of the signature segment to corrupt it.
        char lastChar = token.charAt(token.length() - 1);
        char replacement = lastChar == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, token.length() - 1) + replacement;

        assertThrows(JwtException.class,
                () -> jwtUtil.validateToken(tampered, userDetails));
    }

    @Test
    void validateToken_tokenSignedWithDifferentSecret_isRejected() {
        String token = jwtUtil.generateToken(userDetails);

        JwtUtil otherSecretUtil = new JwtUtil();
        ReflectionTestUtils.setField(otherSecretUtil, "secret",
                "RGlmZmVyZW50U2VjcmV0S2V5Rm9yVGFtcGVyaW5nVGVzdHMxMjM0NTY3ODk=");
        ReflectionTestUtils.setField(otherSecretUtil, "expiration", 3_600_000L);

        assertThrows(JwtException.class,
                () -> otherSecretUtil.validateToken(token, userDetails));
    }

    @Test
    void malformedToken_throwsJwtException() {
        assertThrows(JwtException.class,
                () -> jwtUtil.validateToken("not-a-real-jwt", userDetails));
    }
}
