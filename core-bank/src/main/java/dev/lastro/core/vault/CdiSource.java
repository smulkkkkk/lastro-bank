package dev.lastro.core.vault;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** De onde vem a taxa diária do CDI. */
interface CdiSource {

    record Rate(LocalDate day, BigDecimal dailyPercent) {
    }

    List<Rate> between(LocalDate from, LocalDate to);
}
