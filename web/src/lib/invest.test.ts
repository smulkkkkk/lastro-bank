import { describe, expect, it } from 'vitest'
import { formatPercent, formatPrice, formatQuantity, pricePath, unitsFor } from './invest'

describe('investimentos', () => {
  it('casas decimais seguem o tamanho do preço', () => {
    expect(formatPrice(353321)).toBe('R$ 353.321')
    expect(formatPrice(412.5)).toBe('R$ 412,50')
    expect(formatPrice(5.8215)).toBe('R$ 5,8215')
  })

  it('quantidade e variação', () => {
    expect(formatQuantity('0.00024999', 'BTC')).toBe('0,00024999 BTC')
    expect(formatQuantity('17.09', 'USD')).toBe('17,09 USD')
    expect(formatPercent(1.5)).toBe('+1,50%')
    expect(formatPercent(-0.257)).toBe('−0,26%')
    expect(formatPercent(0)).toBe('0,00%')
  })

  it('a prévia de unidades corta como o servidor', () => {
    expect(unitsFor(100_00, 400_010, 'BTC')).toBe(0.00024999)
    expect(unitsFor(100_00, 5.85, 'USD')).toBe(17.09)
  })

  it('o gráfico ocupa a largura e respeita a folga', () => {
    const path = pricePath([10, 20, 15], 200, 100)
    expect(path.startsWith('M0.0 94.0')).toBe(true)
    expect(path).toContain('L100.0 6.0')
    expect(path.endsWith('L200.0 50.0')).toBe(true)
    expect(pricePath([5], 200, 100)).toBe('')
  })
})
