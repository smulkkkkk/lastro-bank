package dev.lastro.core.card;

import dev.lastro.core.card.CardService.Card;
import dev.lastro.core.shared.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cards")
class CardController {

    record CardPatch(Boolean frozen, Boolean contactless, Boolean primary) {
    }

    private final CardService cards;

    CardController(CardService cards) {
        this.cards = cards;
    }

    @GetMapping
    List<Card> list(@AuthenticationPrincipal Jwt user) {
        return cards.list(UUID.fromString(user.getSubject()));
    }

    @PatchMapping("/{id}")
    Card update(@PathVariable UUID id, @RequestBody CardPatch patch, @AuthenticationPrincipal Jwt user) {
        if (patch.frozen() == null && patch.contactless() == null && patch.primary() == null) {
            throw ApiException.badRequest("request.invalid", "Informe o que mudar no cartão.");
        }
        if (Boolean.FALSE.equals(patch.primary())) {
            throw ApiException.badRequest("card.primary_required",
                    "Para trocar o cartão principal, escolha outro como principal.");
        }
        return cards.update(id, UUID.fromString(user.getSubject()), patch.frozen(), patch.contactless(),
                patch.primary() != null);
    }
}
