package dev.lastro.core.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cada token de renovação vale uma vez. Se um token já usado reaparece, alguém o copiou: a
 * família inteira é revogada e todos precisam entrar de novo.
 *
 * <p>Exceção: trocar de página no meio da renovação descarta a resposta, e o navegador repete o
 * token antigo. Isso é aceito por alguns segundos, se o sucessor nunca foi usado. O sucessor é
 * invalidado na hora; se ele aparecer depois, conta como reuso.
 */
@Service
class RefreshTokenService {

    record Rotation(UUID userId, String newToken) {
    }

    private record Stored(UUID id, UUID familyId, UUID userId, boolean used, boolean justUsed, boolean usable,
            UUID successorId) {
    }

    private record Issued(UUID id, String token) {
    }

    private final JdbcClient jdbc;
    private final SecurityProperties properties;
    private final SecureRandom random = new SecureRandom();

    RefreshTokenService(JdbcClient jdbc, SecurityProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Transactional
    public String start(UUID userId) {
        return issue(userId, UUID.randomUUID()).token();
    }

    /** Devolve vazio em vez de lançar exceção, porque a revogação da família precisa ser confirmada. */
    @Transactional
    public Optional<Rotation> rotate(String token) {
        Optional<Stored> found = findForUpdate(token);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Stored stored = found.get();
        if (stored.used() && !discardUnusedSuccessor(stored)) {
            revokeFamily(stored.familyId());
            return Optional.empty();
        }
        if (!stored.usable()) {
            return Optional.empty();
        }
        Issued issued = issue(stored.userId(), stored.familyId());
        // Repetir não estende a tolerância: vale a hora do primeiro uso.
        jdbc.sql("""
                        UPDATE core.refresh_token SET used_at = coalesce(used_at, now()), successor_id = :successor
                         WHERE id = :id""")
                .param("successor", issued.id())
                .param("id", stored.id())
                .update();
        return Optional.of(new Rotation(stored.userId(), issued.token()));
    }

    private boolean discardUnusedSuccessor(Stored stored) {
        return stored.justUsed() && stored.successorId() != null
                && jdbc.sql("UPDATE core.refresh_token SET used_at = now() WHERE id = :id AND used_at IS NULL")
                        .param("id", stored.successorId())
                        .update() == 1;
    }

    @Transactional
    public void revoke(String token) {
        findForUpdate(token).ifPresent(stored -> revokeFamily(stored.familyId()));
    }

    private Optional<Stored> findForUpdate(String token) {
        return jdbc.sql("""
                        SELECT id, family_id, user_id, successor_id, used_at IS NOT NULL AS used,
                               coalesce(used_at > now() - interval '10 seconds', false) AS just_used,
                               -- Olha a família toda: um token emitido durante a revogação também não vale.
                               expires_at > now() AND NOT EXISTS (
                                   SELECT 1 FROM core.refresh_token f
                                    WHERE f.family_id = t.family_id AND f.revoked_at IS NOT NULL) AS usable
                          FROM core.refresh_token t WHERE token_hash = :hash FOR UPDATE OF t""")
                .param("hash", hash(token))
                .query((rs, n) -> new Stored(rs.getObject("id", UUID.class), rs.getObject("family_id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getBoolean("used"), rs.getBoolean("just_used"),
                        rs.getBoolean("usable"), rs.getObject("successor_id", UUID.class)))
                .optional();
    }

    private void revokeFamily(UUID familyId) {
        jdbc.sql("UPDATE core.refresh_token SET revoked_at = now() WHERE family_id = :id AND revoked_at IS NULL")
                .param("id", familyId)
                .update();
    }

    private Issued issue(UUID userId, UUID familyId) {
        UUID id = UUID.randomUUID();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("""
                        INSERT INTO core.refresh_token (id, family_id, user_id, token_hash, expires_at)
                        VALUES (:id, :family, :user, :hash, :expires)""")
                .param("id", id)
                .param("family", familyId)
                .param("user", userId)
                .param("hash", hash(token))
                .param("expires", OffsetDateTime.now(ZoneOffset.UTC).plus(properties.refreshTokenTtl()))
                .update();
        return new Issued(id, token);
    }

    private static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
