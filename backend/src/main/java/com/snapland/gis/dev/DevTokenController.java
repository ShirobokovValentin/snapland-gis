package com.snapland.gis.dev;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.snapland.gis.auth.JwtProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Development scaffolding only. Issues a JWT for the seeded dev user without
 * any credential verification. Present only when the "dev" profile is active.
 * This is not a security mechanism — it must not be deployed to production.
 */
@RestController
@Profile("dev")
@RequestMapping("/dev")
public class DevTokenController {

    private final NimbusJwtEncoder encoder;
    private final DevProperties devProperties;
    private final JwtProperties jwtProperties;

    public DevTokenController(DevProperties devProperties, JwtProperties jwtProperties) {
        this.devProperties = devProperties;
        this.jwtProperties = jwtProperties;
        SecretKeySpec key = new SecretKeySpec(
            jwtProperties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @PostMapping("/token")
    public TokenResponse issueToken() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer("snapland-dev")
            .subject(devProperties.userId().toString())
            .issuedAt(now)
            .expiresAt(now.plus(jwtProperties.jwtExpiration()))
            .build();
        String token = encoder.encode(
            JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)
        ).getTokenValue();
        return new TokenResponse(token);
    }

    public record TokenResponse(String token) {}
}
