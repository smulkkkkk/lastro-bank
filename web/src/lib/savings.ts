const BUSINESS_DAYS_PER_MONTH = 21

/**
 * Simulação para exibir: aplica o CDI de hoje por todo o período. O rendimento de verdade é
 * calculado no servidor, dia a dia, com a taxa de cada dia.
 */
export function project(
  balanceCents: number,
  monthlyCents: number,
  months: number,
  dailyPercent: number,
  cdiPercent: number,
): { totalCents: number; yieldCents: number } {
  const month = (1 + (dailyPercent / 100) * (cdiPercent / 100)) ** BUSINESS_DAYS_PER_MONTH
  let total = balanceCents
  for (let i = 0; i < months; i++) total = total * month + monthlyCents
  const totalCents = Math.floor(total)
  return { totalCents, yieldCents: totalCents - balanceCents - monthlyCents * months }
}

/** Altura da água, de 0 a 1. Sem meta, a caixinha mostra quanto ela é do total guardado. */
export function waterLevel(balanceCents: number, goalCents: number | null, totalCents: number): number {
  const reference = goalCents ?? totalCents
  if (reference <= 0 || balanceCents <= 0) return 0
  return Math.min(balanceCents / reference, 1)
}

/** Quanto o Lino está inflado. Cresce rápido no começo, para os primeiros reais já aparecerem. */
export function puff(totalCents: number): number {
  return Math.min(Math.log10(1 + totalCents / 100) / 4, 1)
}
