package dev.lastro.core.card;

import dev.lastro.core.shared.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CardService {

    record Card(UUID id, String design, String label, String last4,
            boolean primary, boolean frozen, boolean contactless) {
    }

    private static final String SELECT_OWNED = """
            SELECT c.id, c.design, c.label, c.last4, c.is_primary, c.frozen, c.contactless
              FROM core.card c JOIN core.account a ON a.id = c.account_id
             WHERE a.owner_id = :owner ORDER BY c.position\s""";

    private static final RowMapper<Card> CARD = (rs, n) -> new Card(rs.getObject("id", UUID.class),
            rs.getString("design"), rs.getString("label"), rs.getString("last4"),
            rs.getBoolean("is_primary"), rs.getBoolean("frozen"), rs.getBoolean("contactless"));

    private final JdbcClient jdbc;

    CardService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Faz parte da abertura da conta. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void issueFor(UUID accountId) {
        jdbc.sql("SELECT core.issue_cards(:account)::text").param("account", accountId)
                .query((rs, n) -> accountId).list();
    }

    List<Card> list(UUID ownerId) {
        return jdbc.sql(SELECT_OWNED).param("owner", ownerId).query(CARD).list();
    }

    /** Campo nulo fica como está. Trava todos os cartões, porque só um pode ser o principal. */
    @Transactional
    public Card update(UUID cardId, UUID ownerId, Boolean frozen, Boolean contactless, boolean makePrimary) {
        List<Card> cards = jdbc.sql(SELECT_OWNED + "FOR UPDATE OF c").param("owner", ownerId).query(CARD).list();
        if (cards.stream().noneMatch(card -> card.id().equals(cardId))) {
            throw ApiException.notFound("card.not_found", "Cartão não encontrado.");
        }
        if (makePrimary) {
            cards.stream().filter(Card::primary).forEach(card ->
                    jdbc.sql("UPDATE core.card SET is_primary = false WHERE id = :id").param("id", card.id()).update());
        }
        return jdbc.sql("""
                        UPDATE core.card
                           SET frozen      = coalesce(CAST(:frozen AS boolean), frozen),
                               contactless = coalesce(CAST(:contactless AS boolean), contactless),
                               is_primary  = is_primary OR :primary
                         WHERE id = :id
                        RETURNING id, design, label, last4, is_primary, frozen, contactless""")
                .param("frozen", frozen)
                .param("contactless", contactless)
                .param("primary", makePrimary)
                .param("id", cardId)
                .query(CARD)
                .single();
    }
}
