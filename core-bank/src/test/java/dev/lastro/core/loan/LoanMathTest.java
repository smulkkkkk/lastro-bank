package dev.lastro.core.loan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lastro.core.loan.LoanMath.Part;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoanMathTest {

    private static final BigDecimal RATE = new BigDecimal("2.49");

    @Test
    void knownPriceTable() {
        // R$ 1.000,00 em 12x a 2,49% ao mês: parcela de R$ 97,43 (1000 x 0,0249 / (1 - 1,0249^-12)).
        List<Part> parts = LoanMath.schedule(1_000_00, RATE, 12);
        assertThat(parts.getFirst().totalCents()).isEqualTo(97_43);
        assertThat(parts.getFirst().interestCents()).isEqualTo(24_90);
    }

    @Test
    void principalClosesExactlyForEveryCombination() {
        for (long cents = LoanService.MIN_CENTS; cents <= LoanService.MAX_CENTS; cents += 37_31) {
            for (int n = LoanService.MIN_INSTALLMENTS; n <= LoanService.MAX_INSTALLMENTS; n++) {
                List<Part> parts = LoanMath.schedule(cents, RATE, n);
                assertThat(parts).hasSize(n).allMatch(part -> part.principalCents() > 0 && part.interestCents() >= 0);
                assertThat(parts.stream().mapToLong(Part::principalCents).sum()).isEqualTo(cents);
                long regular = parts.getFirst().totalCents();
                assertThat(parts.subList(0, n - 1)).allMatch(part -> part.totalCents() == regular);
                assertThat(Math.abs(parts.getLast().totalCents() - regular)).as("só centavos").isLessThan(n);
            }
        }
    }

    @Test
    void yearRateIsCompound() {
        assertThat(LoanMath.yearPercent(RATE)).isEqualByComparingTo("34.33");
    }
}
