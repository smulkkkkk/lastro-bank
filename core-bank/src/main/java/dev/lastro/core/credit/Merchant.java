package dev.lastro.core.credit;

/** Lojas inventadas. Usar marca real sugeriria uma parceria que não existe. */
public enum Merchant {
    MARE_CHEIA("Padaria Maré Cheia", "Alimentação"),
    ARRECIFE("Mercado Arrecife", "Mercado"),
    CORRENTEZA("Posto Correnteza", "Transporte"),
    FAROL("Livraria Farol", "Educação"),
    ABISSAL("Cine Abissal", "Lazer"),
    ANCORA("Âncora Eletrônicos", "Compras");

    private final String title;
    private final String category;

    Merchant(String title, String category) {
        this.title = title;
        this.category = category;
    }

    /** Nome e categoria de cada loja, para quem mostra as parceiras. */
    public static java.util.Map<String, String[]> catalog() {
        java.util.Map<String, String[]> names = new java.util.HashMap<>();
        for (Merchant m : values()) {
            names.put(m.name(), new String[] {m.title, m.category});
        }
        return names;
    }

    String title() {
        return title;
    }

    String category() {
        return category;
    }
}
