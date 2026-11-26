const decimal = (digits: number) =>
  new Intl.NumberFormat('pt-BR', { minimumFractionDigits: digits, maximumFractionDigits: digits })

/** Preço em reais com casas de acordo com o tamanho: R$ 353.321 não precisa de centavos; R$ 5,8215 precisa de 4. */
export function formatPrice(price: number): string {
  const digits = price >= 1000 ? 0 : price >= 10 ? 2 : 4
  return `R$ ${decimal(digits).format(price)}`
}

export function formatQuantity(quantity: string, code: string): string {
  const value = Number(quantity)
  return code === 'USD' || code === 'EUR' ? `${decimal(2).format(value)} ${code}` : `${quantity.replace('.', ',')} ${code}`
}

export function formatPercent(value: number): string {
  const sign = value > 0 ? '+' : value < 0 ? '−' : ''
  return `${sign}${decimal(2).format(Math.abs(value))}%`
}

/** Quanto do ativo um valor compra, cortando no menor pedaço negociável, como o servidor faz. */
export function unitsFor(cents: number, ask: number, code: string): number {
  const places = code === 'USD' || code === 'EUR' ? 2 : 8
  const factor = 10 ** places
  return Math.floor(((cents / 100) / ask) * factor) / factor
}

/** Linha do gráfico: x pelo índice, y pela faixa de preço com uma folga em cima e embaixo. */
export function pricePath(prices: number[], width: number, height: number, pad = 6): string {
  if (prices.length < 2) return ''
  const min = Math.min(...prices)
  const max = Math.max(...prices)
  const span = max - min || 1
  return prices
    .map((price, i) => {
      const x = (i / (prices.length - 1)) * width
      const y = pad + (1 - (price - min) / span) * (height - pad * 2)
      return `${i === 0 ? 'M' : 'L'}${x.toFixed(1)} ${y.toFixed(1)}`
    })
    .join('')
}
