package dev.lastro.core.credit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class InvoiceCalendarTest {

    @Test
    void closingDaySendsThePurchaseToTheNextInvoice() {
        assertThat(InvoiceCalendar.reference(LocalDate.of(2026, 11, 24))).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(InvoiceCalendar.reference(LocalDate.of(2026, 11, 25))).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(InvoiceCalendar.reference(LocalDate.of(2026, 12, 31))).isEqualTo(LocalDate.of(2027, 2, 1));
    }

    @Test
    void closesAndIsDueAroundTheReference() {
        LocalDate january = LocalDate.of(2027, 1, 1);
        assertThat(InvoiceCalendar.closesOn(january)).isEqualTo(LocalDate.of(2026, 12, 25));
        assertThat(InvoiceCalendar.dueOn(january)).isEqualTo(LocalDate.of(2027, 1, 5));
    }

    @Test
    void splittingNeverLosesACent() {
        for (long total = 1; total < 3_000; total += 7) {
            for (int n = 1; n <= 12; n++) {
                var parts = InvoiceCalendar.split(total, n);
                assertThat(parts).hasSize(n);
                assertThat(parts.stream().mapToLong(Long::longValue).sum()).isEqualTo(total);
            }
        }
        assertThat(InvoiceCalendar.split(100_00, 3)).containsExactly(33_34L, 33_33L, 33_33L);
    }
}
