package dev.lastro.core.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class UserRepository {

    record User(UUID id, String passwordHash, String role, boolean locked) {
    }

    private final JdbcClient jdbc;

    UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID id, String fullName, String email, String cpf, String passwordHash) {
        jdbc.sql("""
                        INSERT INTO core.app_user (id, full_name, email, cpf, password_hash)
                        VALUES (:id, :name, :email, :cpf, :hash)""")
                .param("id", id)
                .param("name", fullName)
                .param("email", email)
                .param("cpf", cpf)
                .param("hash", passwordHash)
                .update();
    }

    Optional<User> findByEmail(String email) {
        return find("email = :value", email);
    }

    Optional<User> findById(UUID id) {
        return find("id = :value", id);
    }

    /** Uma única instrução, para tentativas simultâneas não se perderem. */
    void recordFailedLogin(UUID id, int limit) {
        jdbc.sql("""
                        UPDATE core.app_user
                           SET locked_until  = CASE WHEN failed_logins + 1 >= :limit
                                                    THEN now() + interval '15 minutes' ELSE locked_until END,
                               failed_logins = CASE WHEN failed_logins + 1 >= :limit THEN 0 ELSE failed_logins + 1 END
                         WHERE id = :id""")
                .param("limit", limit)
                .param("id", id)
                .update();
    }

    void clearFailedLogins(UUID id) {
        jdbc.sql("""
                        UPDATE core.app_user SET failed_logins = 0, locked_until = NULL
                         WHERE id = :id AND (failed_logins > 0 OR locked_until IS NOT NULL)""")
                .param("id", id)
                .update();
    }

    private Optional<User> find(String condition, Object value) {
        return jdbc.sql("""
                        SELECT id, password_hash, role, coalesce(locked_until > now(), false) AS locked
                          FROM core.app_user WHERE\s""" + condition)
                .param("value", value)
                .query((rs, n) -> new User(rs.getObject("id", UUID.class), rs.getString("password_hash"),
                        rs.getString("role"), rs.getBoolean("locked")))
                .optional();
    }
}
