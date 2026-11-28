package dev.lastro.core.openfinance;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lastro.core.support.ApiClient;
import dev.lastro.core.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

class OpenFinanceFlowTest extends IntegrationTest {

    private static final String ALL = "[\"BALANCES\",\"TRANSACTIONS\",\"PAYMENTS\"]";

    private Customer ana;
    private ApiClient api;

    @BeforeEach
    void setUp() throws Exception {
        ana = newCustomer();
        api = new ApiClient(mvc, ana.bearer());
    }

    private JsonNode ask(String bank, String permissions) throws Exception {
        MockHttpServletResponse response = api.send("/open-finance/consents",
                "{\"bank\":\"%s\",\"permissions\":%s,\"months\":6}".formatted(bank, permissions));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return body(response);
    }

    private JsonNode authorize(String id, boolean approve) throws Exception {
        return body(api.send("/open-finance/consents/" + id + "/authorization", "{\"approve\":" + approve + "}"));
    }

    private JsonNode connected(String permissions) throws Exception {
        return authorize(ask("CORAL", permissions).get("id").asString(), true);
    }

    private long balance() {
        return jdbc.sql("SELECT balance_cents FROM core.account WHERE id = :id").param("id", ana.accountId())
                .query(Long.class).single();
    }

    @Test
    void dataOnlyAppearsAfterTheOtherBankAuthorizes() throws Exception {
        JsonNode asked = ask("CORAL", ALL);
        assertThat(asked.get("status").asString()).isEqualTo("AWAITING");
        assertThat(asked.get("account").isNull()).isTrue();

        JsonNode authorized = authorize(asked.get("id").asString(), true);
        assertThat(authorized.get("status").asString()).isEqualTo("AUTHORIZED");
        assertThat(authorized.get("account").get("balanceCents").asLong()).isPositive();
        assertThat(authorized.get("expiresAt").isNull()).isFalse();
        assertThat(body(api.read("/open-finance/consents/" + asked.get("id").asString() + "/transactions"))).isNotEmpty();
    }

    @Test
    void rejectedRequestShowsNothing() throws Exception {
        JsonNode rejected = authorize(ask("PEROLA", ALL).get("id").asString(), false);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("account").isNull()).isTrue();
    }

    @Test
    void onlyTheAuthorizedDataIsShared() throws Exception {
        JsonNode consent = connected("[\"TRANSACTIONS\"]");
        assertThat(consent.get("account").get("balanceCents").isNull()).as("saldo não foi autorizado").isTrue();
        MockHttpServletResponse bring = api.send("/open-finance/consents/" + consent.get("id").asString() + "/transfers",
                "{\"amountCents\":1000}", UUID.randomUUID().toString());
        assertThat(bring.getStatus()).isEqualTo(422);
        assertThat(body(bring).get("code").asString()).isEqualTo("consent.permission");
    }

    @Test
    void revokingCutsAccessImmediately() throws Exception {
        String id = connected(ALL).get("id").asString();
        JsonNode revoked = body(api.delete("/open-finance/consents/" + id));
        assertThat(revoked.get("status").asString()).isEqualTo("REVOKED");
        assertThat(revoked.get("account").isNull()).isTrue();
        assertThat(api.read("/open-finance/consents/" + id + "/transactions").getStatus()).isEqualTo(422);
        // Revogado, dá para pedir de novo.
        ask("CORAL", ALL);
    }

    @Test
    void oneLiveConsentPerBank() throws Exception {
        ask("MANGUE", ALL);
        assertThat(api.send("/open-finance/consents", "{\"bank\":\"MANGUE\",\"permissions\":" + ALL + ",\"months\":3}")
                .getStatus()).isEqualTo(409);
        assertThat(api.send("/open-finance/consents", "{\"bank\":\"MANGUE\",\"permissions\":" + ALL + ",\"months\":5}")
                .getStatus()).isEqualTo(400);
    }

    @Test
    void anotherCustomerCannotSeeOrUseTheConsent() throws Exception {
        String id = connected(ALL).get("id").asString();
        ApiClient bia = new ApiClient(mvc, newCustomer().bearer());
        assertThat(bia.read("/open-finance/consents/" + id + "/transactions").getStatus()).isEqualTo(404);
        assertThat(bia.send("/open-finance/consents/" + id + "/transfers", "{\"amountCents\":100}",
                UUID.randomUUID().toString()).getStatus()).isEqualTo(404);
        assertThat(body(bia.read("/open-finance/consents"))).isEmpty();
    }

    @Test
    void expiredConsentStopsSharing() throws Exception {
        String id = connected(ALL).get("id").asString();
        jdbc.sql("UPDATE core.consent SET expires_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", UUID.fromString(id)).update();
        JsonNode consent = body(api.read("/open-finance/consents")).get(0);
        assertThat(consent.get("status").asString()).isEqualTo("EXPIRED");
        assertThat(consent.get("account").isNull()).isTrue();
        // Vencido não segura o banco: dá para pedir de novo.
        ask("CORAL", ALL);
    }

    @Test
    void bringingMoneyMovesItOnceAndShowsOnBothSides() throws Exception {
        JsonNode consent = connected(ALL);
        String id = consent.get("id").asString();
        long external = consent.get("account").get("balanceCents").asLong();
        String key = UUID.randomUUID().toString();
        JsonNode first = body(api.send("/open-finance/consents/" + id + "/transfers", "{\"amountCents\":25000}", key));
        JsonNode again = body(api.send("/open-finance/consents/" + id + "/transfers", "{\"amountCents\":25000}", key));
        assertThat(again.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(balance()).isEqualTo(1_000_00 + 250_00);
        assertThat(body(api.read("/open-finance/consents")).get(0).get("account").get("balanceCents").asLong())
                .isEqualTo(external - 250_00);
        JsonNode statement = body(api.read("/accounts/" + ana.accountId() + "/statement"));
        assertThat(statement.get("items").get(0).get("description").asString()).isEqualTo("Trazido do Banco Coral");
        JsonNode there = body(api.read("/open-finance/consents/" + id + "/transactions"));
        boolean shown = false;
        for (JsonNode t : there) {
            shown |= t.get("description").asString().equals("Enviado para o Lastro") && t.get("amountCents").asLong() == -250_00;
        }
        assertThat(shown).as("o extrato de lá mostra a saída").isTrue();
    }

    @Test
    void cannotBringMoreThanThereIs() throws Exception {
        String id = connected(ALL).get("id").asString();
        jdbc.sql("UPDATE core.external_account SET balance_cents = 30000 WHERE consent_id = :id")
                .param("id", UUID.fromString(id)).update();
        List<Integer> statuses = contend(ana.accountId(), 2, () -> api.send("/open-finance/consents/" + id + "/transfers",
                "{\"amountCents\":20000}", UUID.randomUUID().toString()).getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 422);
        assertThat(balance()).isEqualTo(1_000_00 + 200_00);
    }
}
