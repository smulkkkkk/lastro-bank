package dev.lastro.core.identity;

/** Valida os dígitos verificadores. Não consulta nem confirma titularidade de ninguém. */
final class Cpf {

    private Cpf() {
    }

    static String normalize(String raw) {
        return raw.replaceAll("[.\\-\\s]", "");
    }

    static boolean isValid(String digits) {
        if (!digits.matches("[0-9]{11}") || digits.chars().distinct().count() == 1) {
            return false;
        }
        return checkDigit(digits, 9) == digits.charAt(9) - '0'
                && checkDigit(digits, 10) == digits.charAt(10) - '0';
    }

    private static int checkDigit(String digits, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += (digits.charAt(i) - '0') * (length + 1 - i);
        }
        int rest = sum * 10 % 11;
        return rest == 10 ? 0 : rest;
    }
}
