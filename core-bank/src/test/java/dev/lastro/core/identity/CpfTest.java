package dev.lastro.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CpfTest {

    @Test
    void acceptsValidDigits() {
        assertThat(Cpf.isValid("52998224725")).isTrue();
        assertThat(Cpf.isValid(Cpf.normalize("529.982.247-25"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"52998224724", "52998224735", "11111111111", "5299822472", "529982247255", "abc", ""})
    void rejectsInvalid(String cpf) {
        assertThat(Cpf.isValid(cpf)).isFalse();
    }
}
