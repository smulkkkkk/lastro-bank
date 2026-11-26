package dev.lastro.core.loan;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Tabela Price: parcelas iguais. A última absorve os centavos de arredondamento. */
final class LoanMath {

    record Part(long principalCents, long interestCents) {
        long totalCents() {
            return principalCents + interestCents;
        }
    }

    private LoanMath() {
    }

    static List<Part> schedule(long principalCents, BigDecimal monthlyPercent, int installments) {
        BigDecimal rate = monthlyPercent.movePointLeft(2);
        BigDecimal growth = BigDecimal.ONE.add(rate).pow(installments, MathContext.DECIMAL128);
        long payment = BigDecimal.valueOf(principalCents).multiply(rate).multiply(growth)
                .divide(growth.subtract(BigDecimal.ONE), 0, RoundingMode.HALF_UP).longValueExact();

        List<Part> parts = new ArrayList<>();
        long owed = principalCents;
        for (int i = 1; i <= installments; i++) {
            long interest = BigDecimal.valueOf(owed).multiply(rate).setScale(0, RoundingMode.HALF_UP).longValueExact();
            long principal = i == installments ? owed : payment - interest;
            parts.add(new Part(principal, interest));
            owed -= principal;
        }
        return parts;
    }

    static BigDecimal yearPercent(BigDecimal monthlyPercent) {
        return BigDecimal.ONE.add(monthlyPercent.movePointLeft(2)).pow(12, MathContext.DECIMAL64)
                .subtract(BigDecimal.ONE).movePointRight(2).setScale(2, RoundingMode.HALF_UP);
    }
}
