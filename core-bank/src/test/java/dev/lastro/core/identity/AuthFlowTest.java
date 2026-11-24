package dev.lastro.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.lastro.core.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthFlowTest extends IntegrationTest {

    @Test
    void registrationOpensAccountWithWelcomeBalance() throws Exception {
        Customer customer = newCustomer();
        var accounts = body(mvc.perform(get("/api/v1/accounts").header("Authorization", customer.bearer()))
                .andReturn().getResponse());
        assertThat(accounts.size()).isEqualTo(1);
        assertThat(accounts.get(0).get("availableCents").asLong()).isEqualTo(1_000_00);
        assertThat(accounts.get(0).get("number").asString()).matches("[0-9]{6}-[0-9]");
    }

    @Test
    void duplicateEmailOrCpfIsRejectedAndLeavesNothingBehind() throws Exception {
        String email = UUID.randomUUID() + "@teste.dev";
        String cpf = randomCpf();
        assertThat(register(email, cpf, PASSWORD).getStatus()).isEqualTo(201);
        long accounts = count("core.account");

        var sameEmail = register(email.toUpperCase(), randomCpf(), PASSWORD);
        var sameCpf = register(UUID.randomUUID() + "@teste.dev", cpf, PASSWORD);

        assertThat(sameEmail.getStatus()).isEqualTo(409);
        assertThat(sameCpf.getStatus()).isEqualTo(409);
        assertThat(body(sameCpf).get("code").asString()).isEqualTo("identity.already_registered");
        assertThat(count("core.account")).isEqualTo(accounts);
    }

    @Test
    void invalidDataIsExplainedByField() throws Exception {
        var response = register("nao-e-email", randomCpf(), "curta");
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        var fields = body(response).get("fields");
        assertThat(fields.has("email")).isTrue();
        assertThat(fields.has("password")).isTrue();
        assertThat(body(response).get("traceId").asString()).matches("[0-9a-f]{32}");

        var cpf = register(UUID.randomUUID() + "@teste.dev", "12345678900", PASSWORD);
        assertThat(body(cpf).get("code").asString()).isEqualTo("identity.invalid_cpf");
    }

    @Test
    void passwordIsStoredAsArgon2id() throws Exception {
        Customer customer = newCustomer();
        String hash = jdbc.sql("SELECT password_hash FROM core.app_user WHERE id = :id")
                .param("id", customer.userId()).query(String.class).single();
        assertThat(hash).startsWith("$argon2id$");
    }

    @Test
    void wrongPasswordUnknownUserAndLockedAccountLookTheSame() throws Exception {
        Customer customer = newCustomer();
        String wrong = null;
        for (int i = 0; i < 5; i++) {
            var response = login(customer.email(), "senha-errada-1");
            assertThat(response.getStatus()).isEqualTo(401);
            wrong = body(response).get("detail").asString();
        }
        var locked = login(customer.email(), PASSWORD);
        var unknown = login("ninguem@teste.dev", PASSWORD);
        assertThat(locked.getStatus()).isEqualTo(401);
        assertThat(body(locked).get("detail").asString()).isEqualTo(wrong);
        assertThat(body(unknown).get("detail").asString()).isEqualTo(wrong);

        jdbc.sql("UPDATE core.app_user SET locked_until = now() - interval '1 second' WHERE id = :id")
                .param("id", customer.userId()).update();
        assertThat(login(customer.email(), PASSWORD).getStatus()).isEqualTo(200);
    }

    @Test
    void refreshCookieIsProtected() throws Exception {
        Customer customer = newCustomer();
        String header = login(customer.email(), PASSWORD).getHeader("Set-Cookie");
        assertThat(header).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth");
    }

    @Test
    void refreshRotatesAndReuseRevokesTheWholeFamily() throws Exception {
        Customer customer = newCustomer();
        Cookie first = customer.refresh();

        var rotated = refresh(first);
        assertThat(rotated.getStatus()).isEqualTo(200);
        Cookie second = rotated.getCookie("lastro_refresh");
        assertThat(second.getValue()).isNotEqualTo(first.getValue());

        assertThat(refresh(first).getStatus()).isEqualTo(401);
        assertThat(refresh(second).getStatus()).as("token novo cai junto").isEqualTo(401);
        assertThat(login(customer.email(), PASSWORD).getStatus()).isEqualTo(200);
    }

    @Test
    void tokenIssuedDuringRevocationIsAlsoDead() throws Exception {
        Customer customer = newCustomer();
        Cookie second = refresh(customer.refresh()).getCookie("lastro_refresh");
        // Reproduz a corrida: a família foi revogada, mas o token mais novo escapou da marca.
        jdbc.sql("UPDATE core.refresh_token SET revoked_at = now() WHERE user_id = :id AND used_at IS NOT NULL")
                .param("id", customer.userId()).update();
        assertThat(refresh(second).getStatus()).isEqualTo(401);
    }

    @Test
    void expiredRefreshAndLogoutEndTheSession() throws Exception {
        Customer expired = newCustomer();
        jdbc.sql("""
                        UPDATE core.refresh_token SET issued_at = now() - interval '2 day',
                               expires_at = now() - interval '1 day' WHERE user_id = :id""")
                .param("id", expired.userId()).update();
        assertThat(refresh(expired.refresh()).getStatus()).isEqualTo(401);

        Customer leaving = newCustomer();
        var logout = mvc.perform(post("/api/v1/auth/logout").cookie(leaving.refresh())).andReturn().getResponse();
        assertThat(logout.getStatus()).isEqualTo(204);
        assertThat(refresh(leaving.refresh()).getStatus()).isEqualTo(401);
    }

    @Test
    void protectedRoutesNeedAValidToken() throws Exception {
        var missing = mvc.perform(get("/api/v1/accounts")).andReturn().getResponse();
        assertThat(missing.getStatus()).isEqualTo(401);
        assertThat(body(missing).get("code").asString()).isEqualTo("auth.required");

        String token = newCustomer().token();
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "AAAA";
        var response = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + tampered))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void jwksExposesOnlyThePublicKey() throws Exception {
        var key = body(mvc.perform(get("/.well-known/jwks.json")).andReturn().getResponse()).get("keys").get(0);
        assertThat(key.get("kty").asString()).isEqualTo("RSA");
        assertThat(key.has("n")).isTrue();
        assertThat(key.has("d")).isFalse();
        assertThat(key.has("p")).isFalse();
    }

    @Test
    void unknownOriginIsRefused() throws Exception {
        var response = mvc.perform(post("/api/v1/auth/refresh").header("Origin", "https://outro.exemplo"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    private MockHttpServletResponse refresh(Cookie cookie) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").cookie(cookie)).andReturn().getResponse();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
