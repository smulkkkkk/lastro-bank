import { describe, expect, it } from 'vitest'
import type { Invoice } from './api'
import { focusInvoice, fullMonth, monthPath, shortDay, shortMonth, usage } from './credit'

const invoice = (reference: string, status: Invoice['status']): Invoice => ({
  reference, status, closesOn: reference, dueOn: reference, totalCents: 1, paidCents: 0, remainingCents: 1,
})

describe('crédito', () => {
  it('mostra a fatura mais urgente primeiro', () => {
    const list = [invoice('2026-11-01', 'PAID'), invoice('2026-12-01', 'OPEN'), invoice('2027-01-01', 'FUTURE')]
    expect(focusInvoice(list)?.reference).toBe('2026-12-01')
    expect(focusInvoice([...list, invoice('2026-10-01', 'OVERDUE')])?.status).toBe('OVERDUE')
    expect(focusInvoice([])).toBeUndefined()
  })

  it('datas não voltam um dia por causa do fuso', () => {
    expect(shortMonth('2027-01-01')).toBe('jan')
    expect(fullMonth('2027-01-01')).toBe('janeiro de 2027')
    expect(shortDay('2027-01-05')).toBe('05 de jan')
    expect(monthPath('2027-01-01')).toBe('2027-01')
  })

  it('uso do limite fica entre 0 e 1', () => {
    expect(usage(750_00, 3_000_00)).toBe(0.25)
    expect(usage(0, 0)).toBe(0)
    expect(usage(5_000_00, 3_000_00)).toBe(1)
  })
})
