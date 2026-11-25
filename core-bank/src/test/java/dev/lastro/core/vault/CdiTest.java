package dev.lastro.core.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class CdiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void readsTheFormatOfBancoCentral() {
        var rates = BcbCdiSource.parse(JSON,
                "[{\"data\":\"19/11/2026\",\"valor\":\"0.050788\"},{\"data\":\"23/11/2026\",\"valor\":\"0.050788\"}]");
        assertThat(rates).hasSize(2);
        assertThat(rates.get(1).day()).isEqualTo(LocalDate.of(2026, 11, 23));
        assertThat(rates.get(1).dailyPercent()).isEqualByComparingTo("0.050788");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}", "<html>", "[{\"data\":\"23/11/2026\"}]", "[{\"data\":\"23/11/2026\",\"valor\":\"0\"}]",
        "[{\"data\":\"23/11/2026\",\"valor\":\"-0.05\"}]", "[{\"data\":\"23/11/2026\",\"valor\":\"13.65\"}]",
        "[{\"data\":\"2026-11-23\",\"valor\":\"0.05\"}]", "[{\"data\":\"23/11/2026\",\"valor\":\"abc\"}]"})
    void refusesAnythingElse(String body) {
        assertThatThrownBy(() -> BcbCdiSource.parse(JSON, body)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void yieldIsExact() {
        // R$ 1.000,00 a 0,050788% = 50,788 centavos; a 110% = 55,8668.
        assertThat(YieldService.exactYield(100_000, new BigDecimal("0.050788"), 100)).isEqualByComparingTo("50.788");
        assertThat(YieldService.exactYield(100_000, new BigDecimal("0.050788"), 110)).isEqualByComparingTo("55.8668");
        assertThat(YieldService.exactYield(0, new BigDecimal("0.05"), 100)).isEqualByComparingTo("0");
    }

    @Test
    void yearRateUsesBusinessDays() {
        assertThat(VaultService.yearPercent(new BigDecimal("0.050788"))).isEqualByComparingTo("13.65");
    }
}
