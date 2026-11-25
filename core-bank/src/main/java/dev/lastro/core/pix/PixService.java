package dev.lastro.core.pix;

import dev.lastro.core.ledger.LedgerService;
import dev.lastro.core.ledger.LedgerService.Kind;
import dev.lastro.core.ledger.Posting;
import dev.lastro.core.pix.PixRepository.Contact;
import dev.lastro.core.pix.PixRepository.Holder;
import dev.lastro.core.pix.PixRepository.Key;
import dev.lastro.core.pix.PixRepository.Receipt;
import dev.lastro.core.pix.PixRepository.Usage;
import dev.lastro.core.shared.ApiException;
import dev.lastro.core.shared.RateLimiter;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PixService {

    static final long DAY_LIMIT_CENTS = 5_000_00;
    static final long NIGHT_LIMIT_CENTS = 2_000_00;
    private static final int MAX_RANDOM_KEYS = 3;
    // Consultar chaves em massa revelaria quem tem conta.
    private static final int LOOKUPS_PER_MINUTE = 20;
    private static final int TRANSFERS_PER_MINUTE = 30;

    enum KeyKind { EMAIL, CPF, RANDOM }

    record Recipient(String key, String name, String maskedCpf) {
    }

    record Limits(long availableCents, long limitCents, boolean night) {
    }

    private final PixRepository pix;
    private final LedgerService ledger;
    private final RateLimiter rateLimiter;
    private final Clock clock;

    PixService(PixRepository pix, LedgerService ledger, RateLimiter rateLimiter, Clock clock) {
        this.pix = pix;
        this.ledger = ledger;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    List<Key> keys(UUID ownerId) {
        return pix.keys(pix.holder(ownerId).accountId());
    }

    /** E-mail e CPF só podem ser os do próprio cadastro; ninguém registra a chave de outra pessoa. */
    @Transactional
    public Key createKey(UUID ownerId, KeyKind kind) {
        Holder holder = pix.holder(ownerId);
        ledger.lock(List.of(holder.accountId()));
        if (kind == KeyKind.RANDOM && pix.keys(holder.accountId()).stream()
                .filter(key -> key.kind().equals(KeyKind.RANDOM.name())).count() >= MAX_RANDOM_KEYS) {
            throw ApiException.unprocessable("pix.too_many_keys", "Você já tem 3 chaves aleatórias.");
        }
        String value = switch (kind) {
            case EMAIL -> holder.email();
            case CPF -> holder.cpf();
            case RANDOM -> UUID.randomUUID().toString();
        };
        Key key = new Key(UUID.randomUUID(), kind.name(), value);
        try {
            pix.insertKey(key, holder.accountId());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("pix.key_exists", "Esta chave já está cadastrada.");
        }
        return key;
    }

    @Transactional
    public void deleteKey(UUID ownerId, UUID keyId) {
        if (!pix.deleteKey(keyId, pix.holder(ownerId).accountId())) {
            throw ApiException.notFound("pix.key_not_found", "Chave não encontrada.");
        }
    }

    Recipient lookup(UUID ownerId, String rawKey) {
        rateLimiter.check("pix-lookup:" + ownerId, LOOKUPS_PER_MINUTE);
        String key = normalize(rawKey);
        Holder recipient = recipient(key);
        rejectSelf(pix.holder(ownerId), recipient);
        return new Recipient(key, recipient.name(), "***." + recipient.cpf().substring(3, 6) + "."
                + recipient.cpf().substring(6, 9) + "-**");
    }

    Limits limits(UUID ownerId) {
        Usage usage = pix.usage(pix.holder(ownerId).accountId(), now());
        return new Limits(available(usage), usage.night() ? NIGHT_LIMIT_CENTS : DAY_LIMIT_CENTS, usage.night());
    }

    List<Contact> contacts(UUID ownerId) {
        return pix.contacts(pix.holder(ownerId).accountId());
    }

    @Transactional
    public Receipt transfer(UUID ownerId, String rawKey, long amountCents, String rawMessage, String idempotencyKey) {
        rateLimiter.check("pix-transfer:" + ownerId, TRANSFERS_PER_MINUTE);
        String key = normalize(rawKey);
        String message = rawMessage == null || rawMessage.isBlank() ? null : rawMessage.strip();
        Holder sender = pix.holder(ownerId);

        Optional<Holder> found = pix.findByKey(key);

        // As duas contas de uma vez: travar só quem paga e depois quem recebe daria deadlock em
        // transferências cruzadas.
        ledger.lock(Stream.concat(Stream.of(sender), found.stream()).map(Holder::accountId).toList());

        // Repetição vem antes da chave: o comprovante continua valendo se quem recebeu apagou a dela.
        Optional<Receipt> previous = pix.findTransfer(sender.accountId(), idempotencyKey);
        if (previous.isPresent()) {
            Receipt receipt = previous.get();
            if (receipt.amountCents() != amountCents || !receipt.key().equals(key)
                    || !Objects.equals(receipt.message(), message)) {
                throw ApiException.conflict("idempotency.mismatch",
                        "Esta Idempotency-Key já foi usada em outra transferência.");
            }
            return receipt;
        }

        Holder recipient = found.orElseThrow(PixService::unknownKey);
        rejectSelf(sender, recipient);
        OffsetDateTime now = now();
        if (amountCents > available(pix.usage(sender.accountId(), now))) {
            throw ApiException.unprocessable("pix.limit", "Este valor passa do seu limite de Pix agora.");
        }
        UUID transactionId = ledger.post(Kind.TRANSFER, "Pix", null, List.of(
                Posting.debit(sender.accountId(), amountCents, "Pix para " + recipient.name()),
                Posting.credit(recipient.accountId(), amountCents, "Pix de " + sender.name())));
        Receipt receipt = new Receipt(UUID.randomUUID(), key, recipient.name(), amountCents, message, now);
        pix.insertTransfer(receipt, sender.accountId(), recipient.accountId(), idempotencyKey, transactionId);
        return receipt;
    }

    static String normalize(String raw) {
        String key = raw.strip().toLowerCase(Locale.ROOT);
        String digits = key.replaceAll("[.\\-\\s]", "");
        return digits.matches("[0-9]{11}") ? digits : key;
    }

    private Holder recipient(String key) {
        return pix.findByKey(key).orElseThrow(PixService::unknownKey);
    }

    private static ApiException unknownKey() {
        return ApiException.notFound("pix.key_not_found", "Nenhuma conta usa esta chave.");
    }

    private static void rejectSelf(Holder sender, Holder recipient) {
        if (sender.accountId().equals(recipient.accountId())) {
            throw ApiException.unprocessable("pix.self", "Esta chave é sua. Escolha a de outra pessoa.");
        }
    }

    private static long available(Usage usage) {
        long available = DAY_LIMIT_CENTS - usage.dayCents();
        if (usage.night()) {
            available = Math.min(available, NIGHT_LIMIT_CENTS - usage.nightCents());
        }
        return Math.max(available, 0);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
