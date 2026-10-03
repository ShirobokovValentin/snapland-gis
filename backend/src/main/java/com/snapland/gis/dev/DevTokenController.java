package com.snapland.gis.dev;

import com.snapland.gis.auth.JwtIssuer;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development scaffolding only. Issues a JWT for the seeded dev user without
 * any credential verification. Present only when the "dev" profile is active.
 * This is not a security mechanism — it must not be deployed to production.
 */
@Hidden
@RestController
@Profile("dev")
@RequestMapping("/dev")
public class DevTokenController {

    private final JwtIssuer jwtIssuer;
    private final DevProperties devProperties;

    public DevTokenController(JwtIssuer jwtIssuer, DevProperties devProperties) {
        this.jwtIssuer = jwtIssuer;
        this.devProperties = devProperties;
    }

    @PostMapping("/token")
    public TokenResponse issueToken() {
        return new TokenResponse(jwtIssuer.issue(devProperties.userId()));
    }

    public record TokenResponse(String token) {}
}
