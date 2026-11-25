import { describe, expect, it } from 'vitest'
import { offsetOf, settle, wrap } from './carousel'

describe('carteira', () => {
  it('coloca um cartão na frente e um de cada lado', () => {
    expect([0, 1, 2].map((i) => offsetOf(i, 0, 3))).toEqual([0, 1, -1])
    expect([0, 1, 2].map((i) => offsetOf(i, 2, 3))).toEqual([1, -1, 0])
  })

  it('continua certo depois de muitas voltas, nos dois sentidos', () => {
    expect(offsetOf(0, 300, 3)).toBe(0)
    expect(offsetOf(1, -299, 3)).toBe(0)
    expect(wrap(-1, 3)).toBe(2)
  })

  it('solta no mais próximo, mas um gesto rápido troca', () => {
    expect(settle(1, 0.3, 0)).toBe(1)
    expect(settle(1, 0.6, 0)).toBe(2)
    expect(settle(1, 0.2, 0.9)).toBe(2)
    expect(settle(1, -0.2, -0.9)).toBe(0)
    expect(settle(1, 0.01, 2)).toBe(1)
  })
})
