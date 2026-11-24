package dev.lastro.core.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccountNumberTest {

    @Test
    void generatedNumbersAreValid() {
        for (long n = 100_000; n < 101_000; n++) {
            assertThat(AccountNumber.isValid(AccountNumber.format(n))).isTrue();
        }
    }

    @Test
    void detectsAnySingleDigitTypo() {
        String number = AccountNumber.format(123_456);
        for (int i = 0; i < 6; i++) {
            for (char digit = '0'; digit <= '9'; digit++) {
                if (digit != number.charAt(i)) {
                    String typo = number.substring(0, i) + digit + number.substring(i + 1);
                    assertThat(AccountNumber.isValid(typo)).as(typo).isFalse();
                }
            }
        }
    }
}
