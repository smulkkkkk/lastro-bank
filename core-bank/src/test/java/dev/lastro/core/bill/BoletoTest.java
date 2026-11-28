package dev.lastro.core.bill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.lastro.core.shared.ApiException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BoletoTest {

    /** Exemplo publicado na documentação do Banco do Brasil: o dígito geral é o 3, na quinta posição. */
    @Test
    void generalDigitMatchesAPublishedBarcode() {
        assertThat(Boleto.generalDigit("00193373700000001000500940144816060680935031")).isEqualTo(3);
    }

    @Test
    void mod10FollowsTheFebrabanRule() {
        // Da direita: 9x2=18 vira 9, depois 9x1 e 1x2. Soma 20, dígito 0.
        assertThat(Boleto.mod10("001900009")).isZero();
        // Soma 25: dígito 5.
        assertThat(Boleto.mod10("001905009")).isEqualTo(5);
        assertThat(Boleto.mod10("0000000000")).isZero();
    }

    @Test
    void lineAndBarcodeAreTheSameBoleto() {
        Boleto created = Boleto.create("777", 123_45, LocalDate.of(2026, 12, 10), "1234567890123456789012345");
        Boleto fromLine = Boleto.parse(created.line());
        Boleto fromBarcode = Boleto.parse(created.barcode());
        assertThat(fromLine).isEqualTo(created).isEqualTo(fromBarcode);
        assertThat(fromLine.amountCents()).isEqualTo(123_45);
        assertThat(fromLine.dueDate()).isEqualTo(LocalDate.of(2026, 12, 10));
        assertThat(created.line()).matches("\\d{5}\\.\\d{5} \\d{5}\\.\\d{6} \\d{5}\\.\\d{6} \\d \\d{14}");
    }

    /** O fator recomeçou em 1000 no dia 22/02/2025. */
    @Test
    void dueDateUsesTheRestartedFactor() {
        assertThat(Boleto.create("777", 100, LocalDate.of(2025, 2, 22), "0".repeat(25)).barcode().substring(5, 9))
                .isEqualTo("1000");
        assertThat(Boleto.create("777", 100, LocalDate.of(2026, 11, 27), "0".repeat(25)).barcode().substring(5, 9))
                .isEqualTo("1643");
    }

    @Test
    void anyTypoIsCaught() {
        String line = Boleto.create("777", 99_90, LocalDate.of(2026, 12, 1), "9876543210987654321098765").line()
                .replaceAll("\\D", "");
        // Trocar qualquer um dos 47 dígitos tem que ser recusado.
        for (int i = 0; i < line.length(); i++) {
            char other = line.charAt(i) == '9' ? '0' : (char) (line.charAt(i) + 1);
            String typo = line.substring(0, i) + other + line.substring(i + 1);
            int position = i;
            assertThatThrownBy(() -> Boleto.parse(typo)).as("dígito %d", position).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void refusesWhatItCannotPay() {
        assertThatThrownBy(() -> Boleto.parse("123")).hasMessageContaining("47 números");
        assertThatThrownBy(() -> Boleto.parse("8".repeat(48))).hasMessageContaining("Conta de consumo");
        assertThatThrownBy(() -> Boleto.parse("abc")).hasMessageContaining("só os números");
    }
}
