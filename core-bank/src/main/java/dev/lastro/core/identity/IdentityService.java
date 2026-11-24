package dev.lastro.core.identity;

import dev.lastro.core.account.AccountService;
import dev.lastro.core.identity.UserRepository.User;
import dev.lastro.core.shared.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class IdentityService {

    private static final int MAX_FAILED_LOGINS = 5;

    record Session(String accessToken, long expiresInSeconds, String refreshToken) {
    }

    private final UserRepository users;
    private final RefreshTokenService refreshTokens;
    private final AccountService accounts;
    private final PasswordEncoder passwords;
    private final JwtEncoder jwtEncoder;
    private final SecurityProperties properties;
    private final TransactionTemplate transaction;
    private final String dummyHash;

    IdentityService(UserRepository users, RefreshTokenService refreshTokens, AccountService accounts,
            PasswordEncoder passwords, JwtEncoder jwtEncoder, SecurityProperties properties,
            TransactionTemplate transaction) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.accounts = accounts;
        this.passwords = passwords;
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.transaction = transaction;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    UUID register(String fullName, String email, String rawCpf, String password) {
        String cpf = Cpf.normalize(rawCpf);
        if (!Cpf.isValid(cpf)) {
            throw ApiException.badRequest("identity.invalid_cpf", "CPF inválido.");
        }
        // O hash é lento de propósito: fica fora da transação para não segurar conexão.
        String hash = passwords.encode(password);
        UUID userId = UUID.randomUUID();
        try {
            transaction.executeWithoutResult(status -> {
                users.insert(userId, fullName.strip(), normalizeEmail(email), cpf, hash);
                accounts.openChecking(userId);
            });
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("identity.already_registered", "E-mail ou CPF já cadastrado.");
        }
        return userId;
    }

    Session login(String email, String password) {
        Optional<User> found = users.findByEmail(normalizeEmail(email));
        // Calcula o hash mesmo sem usuário, para o tempo de resposta não revelar quem tem conta.
        boolean matches = passwords.matches(password, found.map(User::passwordHash).orElse(dummyHash));
        if (found.isEmpty() || found.get().locked()) {
            throw invalidCredentials();
        }
        User user = found.get();
        if (!matches) {
            users.recordFailedLogin(user.id(), MAX_FAILED_LOGINS);
            throw invalidCredentials();
        }
        users.clearFailedLogins(user.id());
        return session(user, refreshTokens.start(user.id()));
    }

    Session refresh(String token) {
        return refreshTokens.rotate(token)
                .flatMap(rotation -> users.findById(rotation.userId())
                        .map(user -> session(user, rotation.newToken())))
                .orElseThrow(() -> ApiException.unauthorized("auth.invalid_refresh", "Sessão expirada. Entre de novo."));
    }

    void logout(String token) {
        refreshTokens.revoke(token);
    }

    private Session session(User user, String refreshToken) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(user.id().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .claim("role", user.role())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new Session(accessToken, properties.accessTokenTtl().toSeconds(), refreshToken);
    }

    private static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static ApiException invalidCredentials() {
        return ApiException.unauthorized("auth.invalid_credentials",
                "E-mail ou senha inválidos, ou acesso temporariamente bloqueado.");
    }
}
