package dev.lastro.core.identity;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import dev.lastro.core.identity.IdentityService.Session;
import dev.lastro.core.shared.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AuthController {

    static final String REFRESH_COOKIE = "lastro_refresh";
    private static final String AUTH_PATH = "/api/v1/auth";

    record RegisterRequest(
            @NotBlank @Size(min = 3, max = 120) String fullName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 14) String cpf,
            @NotNull @Size(min = 10, max = 128) String password) {
    }

    record LoginRequest(@NotBlank @Size(max = 254) String email, @NotNull @Size(max = 128) String password) {
    }

    record RegisterResponse(UUID userId) {
    }

    record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }

    private final IdentityService identity;
    private final SecurityProperties properties;
    private final Map<String, Object> publicKeys;

    AuthController(IdentityService identity, SecurityProperties properties, RSAKey signingKey) {
        this.identity = identity;
        this.properties = properties;
        this.publicKeys = new JWKSet(signingKey.toPublicJWK()).toJSONObject(true);
    }

    @PostMapping(AUTH_PATH + "/register")
    @ResponseStatus(HttpStatus.CREATED)
    RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        return new RegisterResponse(
                identity.register(request.fullName(), request.email(), request.cpf(), request.password()));
    }

    @PostMapping(AUTH_PATH + "/login")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return tokens(identity.login(request.email(), request.password()));
    }

    @PostMapping(AUTH_PATH + "/refresh")
    ResponseEntity<TokenResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String token) {
        if (token == null) {
            throw ApiException.unauthorized("auth.invalid_refresh", "Sessão expirada. Entre de novo.");
        }
        return tokens(identity.refresh(token));
    }

    @PostMapping(AUTH_PATH + "/logout")
    ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String token) {
        if (token != null) {
            identity.logout(token);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO))
                .build();
    }

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() {
        return publicKeys;
    }

    private ResponseEntity<TokenResponse> tokens(Session session) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), properties.refreshTokenTtl()))
                .body(new TokenResponse(session.accessToken(), "Bearer", session.expiresInSeconds()));
    }

    private static String cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true).secure(true).sameSite("Strict").path(AUTH_PATH).maxAge(maxAge)
                .build().toString();
    }
}
