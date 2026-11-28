import { useEffect, useState, type FormEvent } from 'react'
import { ReceiptSheet } from '../components/ReceiptSheet'
import { Alert, Field } from '../components/ui'
import {
  fetchBills,
  fetchReceipt,
  fetchOperators,
  lookupBoleto,
  payBoleto,
  sampleBoleto,
  topUp,
  type BillPayment,
  type BillReceipt,
  type BoletoInfo,
  type Operator,
  type OperatorCode,
} from '../lib/api'
import { usePrivacy } from '../lib/privacy'
import { messageOf } from '../lib/session'
import { useBank } from './AppShell'

const day = new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: 'long', timeZone: 'UTC' })
const when = new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' })

type Tab = 'boleto' | 'recarga'

export function Pay() {
  const { reload } = useBank()
  const { money } = usePrivacy()
  const [tab, setTab] = useState<Tab>('boleto')
  const [history, setHistory] = useState<BillPayment[]>([])
  const [receipt, setReceipt] = useState<BillReceipt>()
  const [error, setError] = useState('')

  const refresh = () => fetchBills().then(setHistory, () => {})
  useEffect(() => {
    void refresh()
  }, [])

  const done = (payment: BillReceipt) => {
    setReceipt(payment)
    void reload()
    void refresh()
  }

  return (
    <section className="pay" aria-labelledby="pay-title">
      <header className="section-head">
        <h1 id="pay-title">Pagar</h1>
        <div className="segmented" role="tablist" aria-label="O que pagar">
          {(['boleto', 'recarga'] as Tab[]).map((option) => (
            <button
              key={option}
              type="button"
              role="tab"
              aria-selected={tab === option}
              onClick={() => {
                setTab(option)
                setReceipt(undefined)
              }}
            >
              {option === 'boleto' ? 'Boleto' : 'Recarga'}
            </button>
          ))}
        </div>
      </header>

      {error && <Alert>{error}</Alert>}
      {receipt ? (
        <ReceiptSheet receipt={receipt} onClose={() => setReceipt(undefined)} />
      ) : tab === 'boleto' ? (
        <BoletoForm onPaid={done} />
      ) : (
        <TopUpForm onDone={done} />
      )}

      {history.length > 0 && (
        <div className="pay-history">
          <h2>Pagamentos recentes</h2>
          <ul>
            {history.map((payment) => (
              <li key={payment.id}>
                <button
                  type="button"
                  aria-label={`Ver comprovante: ${payment.description}`}
                  onClick={() => {
                    setError('')
                    fetchReceipt(payment.id).then(setReceipt, (failure) => setError(messageOf(failure)))
                  }}
                >
                  <span>
                    {payment.description}
                    <small className="muted">{when.format(new Date(payment.createdAt))}</small>
                  </span>
                  <strong>- {money(payment.amountCents)}</strong>
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  )
}

function BoletoForm({ onPaid }: { onPaid: (payment: BillReceipt) => void }) {
  const { money } = usePrivacy()
  const [line, setLine] = useState('')
  const [found, setFound] = useState<BoletoInfo>()
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  // A mesma chave enquanto for o mesmo boleto: tentar de novo depois de uma falha não paga duas vezes.
  const [key, setKey] = useState(() => crypto.randomUUID())

  async function lookup(event: FormEvent) {
    event.preventDefault()
    setError('')
    setBusy(true)
    try {
      setFound(await lookupBoleto(line))
      setKey(crypto.randomUUID())
    } catch (failure) {
      setFound(undefined)
      setError(messageOf(failure))
    } finally {
      setBusy(false)
    }
  }

  async function pay() {
    if (!found) return
    setError('')
    setBusy(true)
    try {
      onPaid(await payBoleto(found.line, key))
    } catch (failure) {
      setError(messageOf(failure))
    } finally {
      setBusy(false)
    }
  }

  async function sample() {
    setError('')
    try {
      const boleto = await sampleBoleto()
      setLine(boleto.line)
      setFound(boleto)
      setKey(crypto.randomUUID())
    } catch (failure) {
      setError(messageOf(failure))
    }
  }

  return (
    <div className="pay-flow">
      <form className="pay-form" onSubmit={(event) => void lookup(event)}>
        <Field
          label="Linha digitável"
          hint="Os 47 números do boleto. Pode colar com pontos e espaços."
          inputMode="numeric"
          autoComplete="off"
          value={line}
          onChange={(event) => {
            setLine(event.target.value)
            setFound(undefined)
          }}
          action={
            <button type="button" className="link-button" onClick={() => void sample()}>
              Gerar boleto de teste
            </button>
          }
        />
        <button type="submit" className="button quiet" disabled={busy || line.replace(/\D/g, '').length < 44}>
          Conferir boleto
        </button>
      </form>

      {found && (
        <div className="boleto-readout">
          <p className="readout-label">Para</p>
          <p className="readout-name">{found.beneficiary}</p>
          <p className="balance">{money(found.amountCents)}</p>
          <p className="muted">
            Vence em {day.format(new Date(found.dueDate))}, banco {found.bank}
          </p>
          <p className="boleto-line">{found.line}</p>
          {found.paid ? (
            <Alert>Este boleto já foi pago.</Alert>
          ) : (
            <button type="button" className="button large" disabled={busy} onClick={() => void pay()}>
              Pagar {money(found.amountCents)}
            </button>
          )}
        </div>
      )}
      {error && <Alert>{error}</Alert>}
    </div>
  )
}

function TopUpForm({ onDone }: { onDone: (payment: BillReceipt) => void }) {
  const { money } = usePrivacy()
  const [operators, setOperators] = useState<Operator[]>([])
  const [operator, setOperator] = useState<OperatorCode>('ONDA')
  const [phone, setPhone] = useState('')
  const [amount, setAmount] = useState<number>()
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [key, setKey] = useState(() => crypto.randomUUID())

  useEffect(() => {
    fetchOperators().then(setOperators, (failure) => setError(messageOf(failure)))
  }, [])

  const digits = phone.replace(/\D/g, '')
  const current = operators.find((item) => item.code === operator)

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!/^[1-9]\d9\d{8}$/.test(digits)) {
      setError('Informe o celular com DDD, como (11) 98765-4321.')
      return
    }
    if (!amount) {
      setError('Escolha um valor.')
      return
    }
    setError('')
    setBusy(true)
    try {
      onDone(await topUp(operator, digits, amount, key))
      setKey(crypto.randomUUID())
    } catch (failure) {
      setError(messageOf(failure))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="pay-flow pay-form" onSubmit={(event) => void submit(event)}>
      <fieldset className="choice">
        <legend>Operadora</legend>
        {operators.map((item) => (
          <label key={item.code} data-checked={operator === item.code}>
            <input type="radio" name="operator" checked={operator === item.code} onChange={() => setOperator(item.code)} />
            {item.name}
          </label>
        ))}
      </fieldset>
      <Field
        label="Celular com DDD"
        inputMode="tel"
        autoComplete="tel-national"
        placeholder="(11) 98765-4321"
        value={phone}
        onChange={(event) => setPhone(event.target.value)}
      />
      <fieldset className="choice">
        <legend>Valor</legend>
        {current?.amountsCents.map((cents) => (
          <label key={cents} data-checked={amount === cents}>
            <input type="radio" name="amount" checked={amount === cents} onChange={() => setAmount(cents)} />
            {money(cents)}
          </label>
        ))}
      </fieldset>
      {error && <Alert>{error}</Alert>}
      <button type="submit" className="button large" disabled={busy}>
        Recarregar{amount ? ` ${money(amount)}` : ''}
      </button>
    </form>
  )
}
