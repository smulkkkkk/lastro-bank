import { describe, expect, it } from 'vitest'
import { gridData } from './betta'

describe('grade do peixe', () => {
  it('cobre a superfície inteira sem apontar para fora', () => {
    const { vertices, surface, strands } = gridData(4, 3)
    expect(vertices.length).toBe(5 * 4 * 2)
    expect(surface.length).toBe(4 * 3 * 6)
    expect(strands.length).toBe(5 * 3 * 2)
    expect(Math.max(...surface, ...strands)).toBe(5 * 4 - 1)
  })

  it('a maior nadadeira cabe em índices de 16 bits', () => {
    expect(191 * 45).toBeLessThan(65536)
  })
})