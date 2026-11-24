package dev.lastro.core.account;

/** Número com dígito verificador de Luhn, que pega erro de digitação antes de chegar ao banco. */
final class AccountNumber {

    private AccountNumber() {
    }

    static String format(long sequence) {
        return sequence + "-" + checkDigit(Long.toString(sequence));
    }

    static boolean isValid(String number) {
        return number.matches("[0-9]{6,12}-[0-9]")
                && checkDigit(number.substring(0, number.length() - 2)) == number.charAt(number.length() - 1) - '0';
    }

    private static int checkDigit(String digits) {
        int sum = 0;
        boolean doubled = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubled) {
                digit = digit * 2 > 9 ? digit * 2 - 9 : digit * 2;
            }
            sum += digit;
            doubled = !doubled;
        }
        return (10 - sum % 10) % 10;
    }
}
