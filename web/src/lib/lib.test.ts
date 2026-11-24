import { describe, expect, it } from 'vitest'
import { fictitiousCpf, formatCpf, isValidCpf } from './cpf'
import { centsFromInput, formatCents } from './money'

describe('dinheiro', () => {
  it('digita pela direita, em centavos', () => {
    expect(centsFromInput('1')).toBe(1)
    expect(centsFromInput('R$ 1.234,56')).toBe(123456)
    expect(centsFromInput('')).toBe(0)
    expect(centsFromInput('abc')).toBe(0)
  })

  it('o que é exibido volta para o mesmo valor', () => {
    for (const cents of [0, 1, 99, 100, 123456, 500000, 999999999]) {
      expect(centsFromInput(formatCents(cents))).toBe(cents)
    }
  })
})

describe('cpf', () => {
  it('confere os dígitos', () => {
    expect(isValidCpf('529.982.247-25')).toBe(true)
    expect(isValidCpf('529.982.247-24')).toBe(false)
    expect(isValidCpf('111.111.111-11')).toBe(false)
    expect(isValidCpf('123')).toBe(false)
  })

  it('gera sempre válidos', () => {
    for (let i = 0; i < 500; i++) expect(isValidCpf(fictitiousCpf())).toBe(true)
  })

  it('formata enquanto digita', () => {
    expect(formatCpf('5299')).toBe('529.9')
    expect(formatCpf('5299822')).toBe('529.982.2')
    expect(formatCpf('52998224725')).toBe('529.982.247-25')
  })
})
