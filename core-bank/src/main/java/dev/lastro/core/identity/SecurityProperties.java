package dev.lastro.core.identity;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lastro.security")
record SecurityProperties(
        String issuer,
        String audience,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        String privateKeyPem,
        boolean allowGeneratedKey,
        List<String> allowedOrigins) {
}
