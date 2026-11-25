export interface Ribbon {
  path: string
  /** Meio da ponta de fora, para alinhar o rótulo. */
  y: number
}

/**
 * Fitas do Rastro. À direita a espessura é exata e as fitas somam o bloco inteiro. À esquerda cada
 * uma tem um espaço igual, para o rótulo de uma parte minúscula não cair em cima do vizinho.
 */
export function ribbons(parts: number[], width: number, height: number, gap = 14): Ribbon[] {
  const total = parts.reduce((sum, part) => sum + part, 0)
  if (total <= 0) return []
  const slot = height / parts.length
  const joined = height - gap
  const middle = width / 2
  const n = (value: number) => Number(value.toFixed(2))
  let right = gap / 2
  return parts.map((part, i) => {
    const thickness = (part / total) * joined
    const center = slot * (i + 0.5)
    const half = Math.max(Math.min(thickness, slot - gap), 2) / 2
    const a = n(center - half)
    const b = n(center + half)
    const c = n(right)
    right += thickness
    const d = n(right)
    return {
      path: `M0 ${a}C${middle} ${a} ${middle} ${c} ${width} ${c}V${d}C${middle} ${d} ${middle} ${b} 0 ${b}Z`,
      y: n(center),
    }
  })
}
