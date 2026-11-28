package dev.lastro.core.bill;

import dev.lastro.core.shared.ApiException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Boleto de cobrança no padrão Febraban. O código de barras tem 44 dígitos:
 *
 * <pre>
 * BBB M D FFFF VVVVVVVVVV LLLLLLLLLLLLLLLLLLLLLLLLL
 * banco, moeda (9), dígito geral, fator de vencimento, valor em centavos, campo livre do banco
 * </pre>
 *
 * A linha digitável (47 dígitos) é o mesmo conteúdo em outra ordem, com um dígito por campo.
 */
public record Boleto(String barcode, String bank, long amountCents, LocalDate dueDate) {

    /** O fator 1000 voltou a ser usado em 22/02/2025, quando a contagem antiga chegou a 9999. */
    static final LocalDate FACTOR_BASE = LocalDate.of(2025, 2, 22);

    /** Aceita a linha digitável ou o código de barras, com ou sem pontos e espaços. */
    public static Boleto parse(String input) {
        String digits = input == null ? "" : input.replaceAll("[\\s.\\-]", "");
        if (!digits.matches("\\d+")) {
            throw invalid("boleto.invalid", "Use só os números do boleto.");
        }
        String barcode = switch (digits.length()) {
            case 47 -> fromLine(digits);
            case 44 -> digits;
            case 48 -> throw invalid("boleto.utility", "Conta de consumo e tributos ainda não são aceitos; só boleto de cobrança.");
            default -> throw invalid("boleto.length", "O boleto tem 47 números na linha digitável.");
        };
        if (barcode.charAt(3) != '9') {
            throw invalid("boleto.currency", "Só boletos em reais são aceitos.");
        }
        if (generalDigit(barcode) != barcode.charAt(4) - '0') {
            throw invalid("boleto.check_digit", "Algum número foi digitado errado: o dígito verificador não confere.");
        }
        long amount = Long.parseLong(barcode.substring(9, 19));
        if (amount <= 0) {
            throw invalid("boleto.no_amount", "Boleto sem valor definido ainda não é aceito.");
        }
        int factor = Integer.parseInt(barcode.substring(5, 9));
        if (factor < 1000) {
            throw invalid("boleto.no_due_date", "Boleto sem vencimento ainda não é aceito.");
        }
        return new Boleto(barcode, barcode.substring(0, 3), amount, FACTOR_BASE.plusDays(factor - 1000L));
    }

    /** Monta um boleto válido. Serve para gerar boletos de teste. */
    static Boleto create(String bank, long amountCents, LocalDate dueDate, String freeField) {
        if (!bank.matches("\\d{3}") || !freeField.matches("\\d{25}") || amountCents <= 0 || amountCents > 9_999_999_999L) {
            throw new IllegalArgumentException("boleto inválido");
        }
        long factor = 1000 + ChronoUnit.DAYS.between(FACTOR_BASE, dueDate);
        if (factor < 1000 || factor > 9999) {
            throw new IllegalArgumentException("vencimento fora da faixa do fator");
        }
        String withoutDigit = bank + "9" + factor + String.format("%010d", amountCents) + freeField;
        String barcode = withoutDigit.substring(0, 4) + generalDigit(withoutDigit.substring(0, 4) + "0" + withoutDigit.substring(4))
                + withoutDigit.substring(4);
        return new Boleto(barcode, bank, amountCents, dueDate);
    }

    /** A linha digitável, com os pontos e espaços de costume. */
    public String line() {
        String b = barcode;
        String f1 = b.substring(0, 4) + b.substring(19, 24);
        String f2 = b.substring(24, 34);
        String f3 = b.substring(34, 44);
        f1 += mod10(f1);
        f2 += mod10(f2);
        f3 += mod10(f3);
        return f1.substring(0, 5) + "." + f1.substring(5) + " " + f2.substring(0, 5) + "." + f2.substring(5) + " "
                + f3.substring(0, 5) + "." + f3.substring(5) + " " + b.charAt(4) + " " + b.substring(5, 19);
    }

    private static String fromLine(String line) {
        String f1 = line.substring(0, 10);
        String f2 = line.substring(10, 21);
        String f3 = line.substring(21, 32);
        for (String field : new String[] {f1, f2, f3}) {
            String body = field.substring(0, field.length() - 1);
            if (mod10(body) != field.charAt(field.length() - 1) - '0') {
                throw invalid("boleto.check_digit", "Algum número foi digitado errado: o dígito verificador não confere.");
            }
        }
        return f1.substring(0, 4) + line.charAt(32) + line.substring(33, 47) + f1.substring(4, 9)
                + f2.substring(0, 10) + f3.substring(0, 10);
    }

    /** Módulo 10: pesos 2 e 1 da direita para a esquerda, somando os algarismos de cada produto. */
    static int mod10(String digits) {
        int sum = 0;
        int weight = 2;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int product = (digits.charAt(i) - '0') * weight;
            sum += product > 9 ? product - 9 : product;
            weight = weight == 2 ? 1 : 2;
        }
        return (10 - sum % 10) % 10;
    }

    /** Módulo 11 sobre os 43 dígitos sem o próprio dígito, pesos de 2 a 9. Resultado 0, 10 ou 11 vira 1. */
    static int generalDigit(String barcode) {
        String digits = barcode.substring(0, 4) + barcode.substring(5);
        int sum = 0;
        int weight = 2;
        for (int i = digits.length() - 1; i >= 0; i--) {
            sum += (digits.charAt(i) - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        int digit = 11 - sum % 11;
        return digit == 0 || digit == 10 || digit == 11 ? 1 : digit;
    }

    private static ApiException invalid(String code, String detail) {
        return ApiException.unprocessable(code, detail);
    }
}
