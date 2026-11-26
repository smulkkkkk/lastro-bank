import { useCallback, useEffect, useId, useRef, useState, type CSSProperties, type FormEvent } from 'react'
import { Link } from 'react-router'
import { Alert, Field } from '../components/ui'
import {
  convertLimit,
  fetchConversionQuote,
  fetchCredit,
  fetchInvoiceItems,
  payInvoice,
  type ConversionQuote,
  type CreditOverview,
  type Invoice,
  type InvoiceItem,
} from '../lib/api'
import { focusInvoice, fullMonth, monthPath, shortDay, shortMonth, STATUS, usage } from '../lib/credit'
import { centsFromInput, formatCents } from '../lib/money'
import { usePrivacy } from '../lib/privacy'
import { messageOf } from '../lib/session'
import { useBank } from './AppShell'

export function Credit() {
  const { account, reload } = useBank()
  const { money } = usePrivacy()
  const [credit, setCredit] = useState<CreditOverview>()
  const [selected, setSelected] = useState<string>()
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [dialog, setDialog] = useState<'pay' | 'convert' | null>(null)

  const load = useCallback(async () => {
    setError('')
    try {
      const result = await fetchCredit()
      setCredit(result)
      setSelected((current) => current ?? focusInvoice(result.invoices)?.reference)
    } catch (failure) {
      setError(messageOf(failure))
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const done = (message: string) => {
    setDialog(null)
    setNotice(message)
    void load()
    void reload()
  }

  if (!credit) {
    return error ? (
      <div className="state">
        <Alert>{error}</Alert>
        <button type="button" className="button" onClick={() => void load()}>
          Tentar de novo
        </button>
      </div>
    ) : (
      <p className="state muted" role="status">
        Carregando crédito…
      </p>
    )
  }

  const invoice = credit.invoices.find((item) => item.reference === selected)

  return (
    <div className="credit">
      <section className="limit" aria-label="Limite">
        <div className="summary">
          <h1 className="balance-label">Limite disponível</h1>
          <p className="balance">{money(credit.availableCents)}</p>
          <div className="limit-bar" style={{ '--used': usage(credit.usedCents, credit.limitCents) } as CSSProperties}>
            <span />
          </div>
          <p className="muted">
            {money(credit.usedCents)} usados de {money(credit.limitCents)}
          </p>
        </div>
        <div className="actions">
          <button type="button" className="button large" disabled={credit.blocked || credit.availableCents < 50_00}
            onClick={() => setDialog('convert')}>
            Converter em saldo
          </button>
          <Link className="button quiet large" to="/app/emprestimo">
            Empréstimo
          </Link>
        </div>
        {credit.blocked && <Alert>Há fatura vencida. Pague para voltar a usar o crédito; o débito continua normal.</Alert>}
        <p className="notice" role="status">
          {notice}
        </p>
      </section>

      <section aria-labelledby="invoices-title">
        <h2 id="invoices-title" className="section-title">
          Faturas
        </h2>
        <div className="months" role="tablist" aria-label="Mês da fatura">
          {credit.invoices.map((item) => (
            <button key={item.reference} type="button" role="tab" aria-selected={item.reference === selected}
              data-status={item.status} onClick={() => setSelected(item.reference)}>
              <span>{shortMonth(item.reference)}</span>
              <small>{STATUS[item.status]}</small>
            </button>
          ))}
        </div>
        {invoice && (
          <InvoiceView key={invoice.reference} invoice={invoice} onPay={() => setDialog('pay')} />
        )}
      </section>

      {dialog === 'pay' && invoice && (
        <PayDialog invoice={invoice} available={account.availableCents} onClose={() => setDialog(null)}
          onDone={() => done('Pagamento feito. O limite já voltou.')} />
      )}
      {dialog === 'convert' && (
        <ConvertDialog available={credit.availableCents} onClose={() => setDialog(null)}
          onDone={(cents) => done(`${formatCents(cents)} entraram no seu saldo.`)} />
      )}
    </div>
  )
}

function InvoiceView({ invoice, onPay }: { invoice: Invoice; onPay: () => void }) {
  const { money } = usePrivacy()
  const [items, setItems] = useState<InvoiceItem[]>()
  const [error, setError] = useState('')

  useEffect(() => {
    fetchInvoiceItems(monthPath(invoice.reference)).then(setItems, (failure) => setError(messageOf(failure)))
  }, [invoice.reference])

  const payable = invoice.remainingCents > 0 && invoice.status !== 'FUTURE'
  return (
    <div className="invoice" data-status={invoice.status}>
      <div className="invoice-head">
        <div>
          <p className="muted">
            Fatura de {fullMonth(invoice.reference)}, {STATUS[invoice.status].toLowerCase()}
          </p>
          <p className="invoice-total">{money(invoice.totalCents)}</p>
          <p className="muted">
            Fecha em {shortDay(invoice.closesOn)} e vence em {shortDay(invoice.dueOn)}
            {invoice.paidCents > 0 && `. Pago: ${money(invoice.paidCents)}`}
          </p>
        </div>
        {payable && (
          <button type="button" className="button" onClick={onPay}>
            {invoice.status === 'OPEN' ? 'Adiantar pagamento' : 'Pagar fatura'}
          </button>
        )}
      </div>
      {error && <Alert>{error}</Alert>}
      {items && items.length === 0 && <p className="muted">Nenhuma compra nesta fatura.</p>}
      {items && items.length > 0 && (
        <ul className="invoice-items">
          {items.map((item) => (
            <li key={`${item.purchaseId}-${item.number}`}>
              <span>
                <strong>{item.merchant}</strong>
                <span className="muted">
                  {item.category}
                  {item.installments > 1 && `, parcela ${item.number} de ${item.installments}`}
                </span>
              </span>
              <strong>{money(item.amountCents)}</strong>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function PayDialog({ invoice, available, onClose, onDone }: { invoice: Invoice; available: number; onClose: () => void; onDone: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const { money } = usePrivacy()
  const key = useRef(crypto.randomUUID())
  const [cents, setCents] = useState(invoice.remainingCents)
  const [error, setError] = useState('')
  const [sending, setSending] = useState(false)

  useEffect(() => dialog.current?.showModal(), [])

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (cents === 0) return setError('Digite um valor.')
    if (cents > invoice.remainingCents) return setError(`Faltam só ${formatCents(invoice.remainingCents)} nesta fatura.`)
    if (cents > available) return setError('Seu saldo não cobre este valor.')
    setSending(true)
    try {
      await payInvoice(monthPath(invoice.reference), cents, key.current)
      onDone()
    } catch (failure) {
      setError(messageOf(failure))
      setSending(false)
    }
  }

  return (
    <dialog ref={dialog} className="dialog" aria-labelledby="pay-title" onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <h2 id="pay-title">Pagar fatura de {fullMonth(invoice.reference)}</h2>
        <p className="muted">Falta {money(invoice.remainingCents)}. Sai do seu saldo, de {money(available)}.</p>
        <Field label="Valor" className="money-input" inputMode="numeric" autoComplete="off" autoFocus
          value={formatCents(cents)} onChange={(event) => {
            key.current = crypto.randomUUID()
            setCents(centsFromInput(event.target.value))
            setError('')
          }} />
        {error && <Alert>{error}</Alert>}
        <div className="dialog-actions">
          <button type="button" className="button quiet" onClick={() => dialog.current?.close()}>
            Cancelar
          </button>
          <button className="button" disabled={sending}>
            {sending ? 'Pagando…' : 'Pagar'}
          </button>
        </div>
      </form>
    </dialog>
  )
}

function ConvertDialog({ available, onClose, onDone }: { available: number; onClose: () => void; onDone: (cents: number) => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const ids = { installments: useId() }
  const key = useRef(crypto.randomUUID())
  const [cents, setCents] = useState(Math.min(500_00, available))
  const [installments, setInstallments] = useState(1)
  const [quote, setQuote] = useState<ConversionQuote>()
  const [error, setError] = useState('')
  const [sending, setSending] = useState(false)

  useEffect(() => dialog.current?.showModal(), [])

  // O custo vem do servidor: a tela nunca calcula uma taxa que depois não é a cobrada.
  useEffect(() => {
    key.current = crypto.randomUUID()
    setQuote(undefined)
    if (cents < 50_00) return
    const timer = setTimeout(() => {
      fetchConversionQuote(cents, installments).then(setQuote, (failure) => setError(messageOf(failure)))
    }, 300)
    return () => clearTimeout(timer)
  }, [cents, installments])

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (cents < 50_00) return setError('O mínimo é R$ 50,00.')
    if (!quote) return
    if (quote.totalCents > available) return setError('Com a taxa, passa do seu limite disponível.')
    setSending(true)
    try {
      await convertLimit(cents, installments, quote.totalCents, key.current)
      onDone(cents)
    } catch (failure) {
      setError(messageOf(failure))
      setSending(false)
    }
  }

  return (
    <dialog ref={dialog} className="dialog" aria-labelledby="convert-title" onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <h2 id="convert-title">Converter limite em saldo</h2>
        <p className="muted">O valor entra na hora no seu saldo e vai para a fatura, com a taxa abaixo.</p>
        <Field label="Quanto você quer" className="money-input" inputMode="numeric" autoComplete="off" autoFocus
          value={formatCents(cents)} onChange={(event) => {
            setCents(centsFromInput(event.target.value))
            setError('')
          }} />
        <div className="field">
          <label htmlFor={ids.installments}>Pagar em</label>
          <select id={ids.installments} value={installments} onChange={(event) => setInstallments(Number(event.target.value))}>
            {[1, 2, 3, 4, 5, 6].map((n) => (
              <option key={n} value={n}>
                {n === 1 ? 'Uma vez, na próxima fatura' : `${n} parcelas`}
              </option>
            ))}
          </select>
        </div>
        <dl className="quote" aria-live="polite">
          {quote ? (
            <>
              <div>
                <dt>Taxa</dt>
                <dd>{formatCents(quote.feeCents)}</dd>
              </div>
              <div>
                <dt>Vai para a fatura</dt>
                <dd>{formatCents(quote.totalCents)}</dd>
              </div>
              {installments > 1 && (
                <div>
                  <dt>Parcelas</dt>
                  <dd>
                    {installments}x de {formatCents(quote.otherInstallmentsCents)}
                  </dd>
                </div>
              )}
            </>
          ) : (
            <div>
              <dt>Taxa</dt>
              <dd className="muted">{cents < 50_00 ? 'A partir de R$ 50,00' : 'Calculando…'}</dd>
            </div>
          )}
        </dl>
        {error && <Alert>{error}</Alert>}
        <div className="dialog-actions">
          <button type="button" className="button quiet" onClick={() => dialog.current?.close()}>
            Cancelar
          </button>
          <button className="button" disabled={sending || !quote}>
            {sending ? 'Convertendo…' : quote ? `Receber ${formatCents(cents)}` : 'Receber'}
          </button>
        </div>
      </form>
    </dialog>
  )
}
