const brl = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })

/** Recebe centavos. A divisão serve só para exibir; nenhuma conta é feita com o resultado. */
export function formatCents(cents: number): string {
  return brl.format(cents / 100)
}

/** Campo de dinheiro: cada dígito entra pela direita, como em maquininha. */
export function centsFromInput(text: string): number {
  const digits = text.replace(/\D/g, '').slice(0, 9)
  return digits === '' ? 0 : Number(digits)
}
