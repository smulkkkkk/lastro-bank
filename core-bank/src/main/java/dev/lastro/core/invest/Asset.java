package dev.lastro.core.invest;

/** O que dá para comprar. Descrições factuais: o app não recomenda nada a ninguém. */
enum Asset {
    BTC("Bitcoin", "Criptomoeda", "Muito alto", 8,
            "Moeda digital sem banco central. O preço pode cair ou subir muito no mesmo dia."),
    ETH("Ethereum", "Criptomoeda", "Muito alto", 8,
            "Criptomoeda da rede Ethereum, usada para rodar contratos e aplicativos."),
    SOL("Solana", "Criptomoeda", "Muito alto", 8,
            "Criptomoeda de uma rede mais nova e mais rápida, com oscilação ainda maior."),
    USD("Dólar", "Moeda", "Médio", 2,
            "Moeda dos Estados Unidos. Sobe quando o real perde valor."),
    EUR("Euro", "Moeda", "Médio", 2,
            "Moeda da zona do euro. Costuma andar perto do dólar, com diferenças.");

    private final String title;
    private final String category;
    private final String risk;
    private final int decimals;
    private final String description;

    Asset(String title, String category, String risk, int decimals, String description) {
        this.title = title;
        this.category = category;
        this.risk = risk;
        this.decimals = decimals;
        this.description = description;
    }

    String title() {
        return title;
    }

    String category() {
        return category;
    }

    String risk() {
        return risk;
    }

    /** Menor fração negociável: satoshi no bitcoin, centavo no dólar. */
    int decimals() {
        return decimals;
    }

    String description() {
        return description;
    }
}
