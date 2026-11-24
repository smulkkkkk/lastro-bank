/** Rosácea de gravura, como a das cédulas: uma curva que oscila em torno de um círculo. */
export function rosette(cx: number, cy: number, radius: number, depth: number, petals: number, phase = 0): string {
  const steps = petals * 16
  let path = ''
  for (let i = 0; i <= steps; i++) {
    const angle = (i / steps) * Math.PI * 2
    const r = radius + depth * Math.sin(petals * angle + phase)
    const x = (cx + r * Math.cos(angle)).toFixed(1)
    const y = (cy + r * Math.sin(angle)).toFixed(1)
    path += `${i === 0 ? 'M' : 'L'}${x} ${y}`
  }
  return path + 'Z'
}

export function guilloche(cx: number, cy: number, size: number): string[] {
  const paths: string[] = []
  for (let i = 0; i < 9; i++) {
    const radius = size * (0.28 + i * 0.085)
    paths.push(rosette(cx, cy, radius, size * 0.09, 12, i * 0.5))
    paths.push(rosette(cx, cy, radius, size * 0.09, 12, Math.PI - i * 0.5))
  }
  return paths
}
