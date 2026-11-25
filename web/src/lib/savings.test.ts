import { describe, expect, it } from 'vitest'
import { project, puff, waterLevel } from './savings'

describe('simulação', () => {
  it('sem tempo, sem rendimento', () => {
    expect(project(100_000, 5_000, 0, 0.050788, 100)).toEqual({ totalCents: 100_000, yieldCents: 0 })
  })

  it('12 meses de R$ 1.000,00 a 100% dão perto do CDI do ano', () => {
    const { yieldCents } = project(100_000, 0, 12, 0.050788, 100)
    expect(yieldCents).toBe(13_649) // 13,65% com 252 dias úteis
  })

  it('aportes não contam como rendimento', () => {
    const { totalCents, yieldCents } = project(0, 10_000, 10, 0, 100)
    expect(totalCents).toBe(100_000)
    expect(yieldCents).toBe(0)
  })

  it('110% rende mais que 100%', () => {
    expect(project(100_000, 0, 12, 0.05, 110).yieldCents).toBeGreaterThan(project(100_000, 0, 12, 0.05, 100).yieldCents)
  })
})

describe('água e Lino', () => {
  it('a água segue a meta e não transborda', () => {
    expect(waterLevel(2_500, 10_000, 99_999)).toBe(0.25)
    expect(waterLevel(20_000, 10_000, 20_000)).toBe(1)
    expect(waterLevel(0, 10_000, 0)).toBe(0)
    expect(waterLevel(2_500, null, 10_000)).toBe(0.25)
    expect(waterLevel(0, null, 0)).toBe(0)
  })

  it('o Lino infla do zero ao máximo', () => {
    expect(puff(0)).toBe(0)
    expect(puff(100_000)).toBeGreaterThan(0.7)
    expect(puff(100_000_000)).toBe(1)
  })
})
