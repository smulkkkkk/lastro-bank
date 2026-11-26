import { useCallback, useEffect, useId, useRef, useState, type CSSProperties } from 'react'
import { Alert } from '../components/ui'
import { fetchLoanQuote, fetchLoans, payLoan, takeLoan, type Loan as LoanData, type LoanOverview, type LoanQuote } from '../lib/api'
import { shortDay } from '../lib/credit'
import { formatCents } from '../lib/money'
import { usePrivacy } from '../lib/privacy'
import { messageOf } from '../lib/session'
import { useBank } from './AppShell'

const percent = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

export function Loan() {
  const { reload } = useBank()
  const [overview, setOverview] = useState<LoanOverview>()
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  const load = useCallback(async () => {
    setError('')
    try {
      setOverview(await fetchLoans())
    } catch (failure) {
      setError(messageOf(failure))
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const done = (message: string) => {
    setNotice(message)
    void load()
    void reload()
  }

  if (!overview) {
    return error ? (
      <div className="state">
        <Alert>{error}</Alert>
        <button type="button" className="button" onClick={() => void load()}>
          Tentar de novo
        </button>
      </div>
    ) : (
      <p className="state muted" role="status">
        Carregando empréstimo…
      </p>
    )
  }

  return (
    <div className="loan">
      <h1>Empréstimo</h1>
      <p className="notice" role="status">
        {notice}
      </p>
      {overview.active ? (
        <Active loan={overview.active} onDone={done} />
      ) : (
        <Simulator overview={overview} onDone={() => done('Dinheiro na conta. A primeira parcela vence em um mês.')} />
      )}
    </div>
  )
}

function Simulator({ overview, onDone }: { overview: LoanOverview; onDone: () => void }) {
  const { offer } = overview
  const ids = { amount: useId(), installments: useId() }
  const key = useRef(crypto.randomUUID())
  const [cents, setCents] = useState(1_000_00)
  const [installments, setInstallments] = useState(12)
  const [quote, setQuote] = useState<LoanQuote>()
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState('')
  const [sending, setSending] = useState(false)

  // As parcelas vêm do servidor, com a mesma conta que ele usa para cobrar.
  useEffect(() => {
    key.current = crypto.randomUUID()
    setConfirming(false)
    const timer = setTimeout(() => {
      fetchLoanQuote(cents, installments).then(setQuote, (failure) => setError(messageOf(failure)))
    }, 250)
    return () => clearTimeout(timer)
  }, [cents, installments])

  const take = async () => {
    if (!quote) return
    setSending(true)
    setError('')
    try {
      await takeLoan(cents, installments, quote.totalCents, key.current)
      onDone()
    } catch (failure) {
      setError(messageOf(failure))
      setSending(false)
    }
  }

  return (
    <section className="loan-sim" aria-label="Simular empréstimo">
      <div className="simulator-controls">
        <label htmlFor={ids.amount}>
          Quanto <strong>{formatCents(cents)}</strong>
        </label>
        <input id={ids.amount} type="range" min={offer.minCents} max={offer.maxCents} step={50_00} value={cents}
          aria-valuetext={formatCents(cents)} onChange={(event) => setCents(Number(event.target.value))} />
        <label htmlFor={ids.installments}>
          Em <strong>{installments} parcelas</strong>
        </label>
        <input id={ids.installments} type="range" min={offer.minInstallments} max={offer.maxInstallments} value={installments}
          aria-valuetext={`${installments} parcelas`} onChange={(event) => setInstallments(Number(event.target.value))} />
      </div>

      <div className="loan-quote" aria-live="polite">
        <p className="muted">Parcela</p>
        <p className="balance">{quote ? formatCents(quote.installmentCents) : '…'}</p>
        {quote && (
          <dl>
            <div>
              <dt>Juros</dt>
              <dd>
                {percent.format(offer.monthlyPercent)}% ao mês, {percent.format(offer.yearPercent)}% ao ano
              </dd>
            </div>
            <div>
              <dt>Você paga no total</dt>
              <dd>{formatCents(quote.totalCents)}</dd>
            </div>
            <div>
              <dt>Desse total, juros</dt>
              <dd>{formatCents(quote.interestCents)}</dd>
            </div>
            <div>
              <dt>Primeira parcela</dt>
              <dd>{shortDay(quote.firstDueOn)}</dd>
            </div>
          </dl>
        )}
        {error && <Alert>{error}</Alert>}
        {confirming && quote ? (
          <div className="confirm">
            <p>
              Você recebe <strong>{formatCents(cents)}</strong> agora e paga <strong>{installments}x de {formatCents(quote.installmentCents)}</strong>.
            </p>
            <div className="actions">
              <button type="button" className="button" disabled={sending} onClick={() => void take()}>
                {sending ? 'Contratando…' : 'Confirmar'}
              </button>
              <button type="button" className="button quiet" disabled={sending} onClick={() => setConfirming(false)}>
                Voltar
              </button>
            </div>
          </div>
        ) : (
          <button type="button" className="button large" disabled={!quote} onClick={() => setConfirming(true)}>
            Contratar
          </button>
        )}
        <p className="muted small">Dinheiro fictício. Taxa fixa de demonstração, sem análise de crédito.</p>
      </div>
    </section>
  )
}

function Active({ loan, onDone }: { loan: LoanData; onDone: (message: string) => void }) {
  const { money } = usePrivacy()
  const key = useRef(crypto.randomUUID())
  const [confirming, setConfirming] = useState<'NEXT' | 'ALL' | null>(null)
  const [error, setError] = useState('')
  const [sending, setSending] = useState(false)
  const paid = loan.installments.filter((item) => item.paidOn).length
  const total = loan.installments.length

  const pay = async (kind: 'NEXT' | 'ALL') => {
    const cents = kind === 'NEXT' ? loan.nextCents : loan.settleCents
    if (cents === null) return
    setSending(true)
    setError('')
    try {
      await payLoan(loan.id, kind, cents, key.current)
      key.current = crypto.randomUUID()
      setConfirming(null)
      setSending(false)
      onDone(kind === 'NEXT' ? 'Parcela paga.' : 'Empréstimo quitado.')
    } catch (failure) {
      setError(messageOf(failure))
      setSending(false)
    }
  }

  return (
    <section className="loan-active" aria-label="Empréstimo em andamento">
      <div className="summary">
        <p className="balance-label">Emprestado em {shortDay(loan.createdOn)}</p>
        <p className="balance">{money(loan.principalCents)}</p>
        <div className="limit-bar" style={{ '--used': paid / total } as CSSProperties}>
          <span />
        </div>
        <p className="muted">
          {paid} de {total} parcelas pagas
        </p>
      </div>
      {error && <Alert>{error}</Alert>}
      {confirming ? (
        <div className="confirm">
          <p>
            {confirming === 'NEXT' ? 'Pagar a próxima parcela' : 'Quitar tudo agora'} por{' '}
            <strong>{money((confirming === 'NEXT' ? loan.nextCents : loan.settleCents) ?? 0)}</strong>, do seu saldo?
          </p>
          <div className="actions">
            <button type="button" className="button" disabled={sending} onClick={() => void pay(confirming)}>
              {sending ? 'Pagando…' : 'Confirmar'}
            </button>
            <button type="button" className="button quiet" disabled={sending} onClick={() => setConfirming(null)}>
              Voltar
            </button>
          </div>
        </div>
      ) : (
        <div className="actions">
          <button type="button" className="button large" onClick={() => setConfirming('NEXT')}>
            Pagar parcela de {money(loan.nextCents ?? 0)}
          </button>
          <button type="button" className="button quiet large" onClick={() => setConfirming('ALL')}>
            Quitar por {money(loan.settleCents ?? 0)}
          </button>
        </div>
      )}
      <p className="muted small">Ao quitar, os juros dos meses que ainda não chegaram saem da conta.</p>
      <ol className="installments">
        {loan.installments.map((item) => (
          <li key={item.number} data-state={item.paidOn ? 'paid' : item.overdue ? 'overdue' : 'open'}>
            <span>
              <strong>Parcela {item.number}</strong>
              <span className="muted">
                {item.paidOn ? `Paga em ${shortDay(item.paidOn)}` : `${item.overdue ? 'Venceu' : 'Vence'} em ${shortDay(item.dueOn)}`}
              </span>
            </span>
            <strong>{money(item.paidCents ?? item.principalCents + item.interestCents)}</strong>
          </li>
        ))}
      </ol>
    </section>
  )
}
