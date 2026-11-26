import type { Invoice } from './api'

const month = new Intl.DateTimeFormat('pt-BR', { month: 'short', timeZone: 'UTC' })
const longMonth = new Intl.DateTimeFormat('pt-BR', { month: 'long', year: 'numeric', timeZone: 'UTC' })
const day = new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: 'short', timeZone: 'UTC' })

/** Datas sem hora chegam como AAAA-MM-DD; ler em UTC evita cair no dia anterior. */
const utc = (iso: string) => new Date(`${iso.slice(0, 10)}T00:00:00Z`)

export const shortMonth = (iso: string) => month.format(utc(iso)).replace('.', '')
export const fullMonth = (iso: string) => longMonth.format(utc(iso))
export const shortDay = (iso: string) => day.format(utc(iso)).replace('.', '')
export const monthPath = (iso: string) => iso.slice(0, 7)

export const STATUS: Record<Invoice['status'], string> = {
  OPEN: 'Aberta',
  CLOSED: 'Fechada',
  OVERDUE: 'Vencida',
  PAID: 'Paga',
  FUTURE: 'Futura',
}

/** A fatura que interessa primeiro: vencida, depois fechada, depois a aberta. */
export function focusInvoice(invoices: Invoice[]): Invoice | undefined {
  for (const status of ['OVERDUE', 'CLOSED', 'OPEN'] as const) {
    const found = invoices.find((invoice) => invoice.status === status)
    if (found) return found
  }
  return invoices[0]
}

/** Quanto do limite está usado, de 0 a 1. */
export const usage = (used: number, limit: number) => (limit <= 0 ? 0 : Math.min(Math.max(used / limit, 0), 1))
