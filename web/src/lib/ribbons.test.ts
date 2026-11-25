import { describe, expect, it } from 'vitest'
import { ribbons } from './ribbons'

/** Pontas de uma fita: esquerda (cima, baixo) e direita (cima, baixo). */
function ends(path: string) {
  const v = path.match(/-?\d+(\.\d+)?/g)!.map(Number)
  return { leftTop: v[1]!, rightTop: v[7]!, rightBottom: v[8]!, leftBottom: v.at(-1)! }
}

describe('fitas do Rastro', () => {
  it('à direita a espessura é proporcional e as fitas se emendam', () => {
    const [one, three] = ribbons([100, 300], 200, 114).map((r) => ends(r.path))
    expect(one!.rightBottom - one!.rightTop).toBeCloseTo(25)
    expect(three!.rightBottom - three!.rightTop).toBeCloseTo(75)
    expect(three!.rightTop).toBe(one!.rightBottom)
  })

  it('partes minúsculas continuam visíveis e com rótulos separados', () => {
    const all = ribbons([1, 1, 99_998], 200, 192)
    expect(all.map((r) => r.y)).toEqual([32, 96, 160])
    for (const { path } of all) {
      const { leftTop, leftBottom } = ends(path)
      expect(leftBottom - leftTop).toBeGreaterThanOrEqual(2)
      expect(leftTop).toBeGreaterThanOrEqual(0)
      expect(leftBottom).toBeLessThanOrEqual(192)
    }
  })

  it('sem partes não desenha nada', () => {
    expect(ribbons([], 200, 100)).toEqual([])
  })
})
