/** Distância de um cartão até o da frente, dando a volta pelo caminho mais curto. */
export function offsetOf(index: number, position: number, count: number): number {
  const raw = (((index - position) % count) + count) % count
  return raw > count / 2 ? raw - count : raw
}

/** Para onde o carrossel vai quando o dedo solta: um gesto rápido já troca. */
export function settle(start: number, dragged: number, velocity: number): number {
  if (Math.abs(velocity) > 0.4 && Math.abs(dragged) > 0.05) return start + Math.sign(dragged)
  return start + Math.round(dragged)
}

export function wrap(position: number, count: number): number {
  return ((position % count) + count) % count
}
