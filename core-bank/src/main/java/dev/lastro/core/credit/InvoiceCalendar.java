package dev.lastro.core.credit;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/** A fatura fecha no dia 25 e vence no dia 5 do mês seguinte, que é o mês de referência dela. */
final class InvoiceCalendar {

    private static final int CLOSING_DAY = 25;
    private static final int DUE_DAY = 5;

    private InvoiceCalendar() {
    }

    /** Em que fatura entra uma compra. No próprio dia do fechamento, já é a próxima. */
    static LocalDate reference(LocalDate day) {
        YearMonth closing = YearMonth.from(day).plusMonths(day.getDayOfMonth() < CLOSING_DAY ? 0 : 1);
        return closing.plusMonths(1).atDay(1);
    }

    static LocalDate closesOn(LocalDate reference) {
        return reference.minusMonths(1).withDayOfMonth(CLOSING_DAY);
    }

    static LocalDate dueOn(LocalDate reference) {
        return reference.withDayOfMonth(DUE_DAY);
    }

    /** Divide sem perder centavo: o que sobra da divisão vai na primeira parcela. */
    static List<Long> split(long totalCents, int installments) {
        long each = totalCents / installments;
        List<Long> parts = new ArrayList<>();
        parts.add(each + totalCents % installments);
        for (int i = 1; i < installments; i++) {
            parts.add(each);
        }
        return parts;
    }
}
