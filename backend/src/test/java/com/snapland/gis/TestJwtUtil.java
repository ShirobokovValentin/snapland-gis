package com.snapland.gis;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Test-only utility that generates and decodes signed JWTs for the application's
 * configured JwtDecoder. Does not depend on any Spring bean or profile.
 */
public final class TestJwtUtil {

    private TestJwtUtil() {}

    public static String generateToken(String jwtSecret, UUID userId) {
        Instant now = Instant.now();
        return build(jwtSecret, userId, now, now.plusSeconds(3600));
    }

    /** Produces a token whose expiry is already in the past (rejected by JwtDecoder). */
    public static String generateExpiredToken(String jwtSecret, UUID userId) {
        Instant expiresAt = Instant.now().minusSeconds(120);
        return build(jwtSecret, userId, expiresAt.minusSeconds(3600), expiresAt);
    }

    public static String extractSubject(String jwtSecret, String token) {
        SecretKeySpec key = new SecretKeySpec(
            jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()
            .decode(token).getSubject();
    }

    private static String build(String jwtSecret, UUID userId, Instant issuedAt, Instant expiresAt) {
        SecretKeySpec key = new SecretKeySpec(
            jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer("snapland")
            .subject(userId.toString())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .build();
        return encoder.encode(
            JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)
        ).getTokenValue();
    }
}
