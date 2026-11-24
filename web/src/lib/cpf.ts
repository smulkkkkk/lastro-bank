function checkDigit(digits: string): string {
  const weight = digits.length + 1
  const sum = [...digits].reduce((total, digit, i) => total + Number(digit) * (weight - i), 0)
  const rest = (sum * 10) % 11
  return String(rest === 10 ? 0 : rest)
}

export function isValidCpf(text: string): boolean {
  const digits = text.replace(/\D/g, '')
  if (digits.length !== 11 || /^(\d)\1+$/.test(digits)) return false
  const first = checkDigit(digits.slice(0, 9))
  return digits.slice(9) === first + checkDigit(digits.slice(0, 9) + first)
}

/** CPF inventado com dígitos válidos, para ninguém precisar usar o próprio aqui. */
export function fictitiousCpf(random: () => number = Math.random): string {
  let base: string
  do {
    base = Array.from({ length: 9 }, () => Math.floor(random() * 10)).join('')
  } while (/^(\d)\1+$/.test(base))
  const first = checkDigit(base)
  return formatCpf(base + first + checkDigit(base + first))
}

export function formatCpf(text: string): string {
  const d = text.replace(/\D/g, '').slice(0, 11)
  return d
    .replace(/^(\d{3})(\d)/, '$1.$2')
    .replace(/^(\d{3})\.(\d{3})(\d)/, '$1.$2.$3')
    .replace(/\.(\d{3})(\d)/, '.$1-$2')
}
