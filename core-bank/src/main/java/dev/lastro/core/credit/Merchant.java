package dev.lastro.core.credit;

/** Lojas inventadas. Usar marca real sugeriria uma parceria que não existe. */
enum Merchant {
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

    String title() {
        return title;
    }

    String category() {
        return category;
    }
}
