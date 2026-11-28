import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Alert } from '../components/ui'
import {
  authorizeConsent,
  bringMoney,
  fetchBanks,
  fetchConsents,
  fetchExternalTransactions,
  requestConsent,
  revokeConsent,
  type Consent,
  type ExternalBank,
  type ExternalTransaction,
  type Permission,
} from '../lib/api'
import { centsFromInput, formatCents } from '../lib/money'
import { usePrivacy } from '../lib/privacy'
import { messageOf } from '../lib/session'
import { useBank } from './AppShell'

const PERMISSIONS: { code: Permission; title: string; detail: string }[] = [
  { code: 'BALANCES', title: 'Saldo', detail: 'Quanto você tem na conta de lá.' },
  { code: 'TRANSACTIONS', title: 'Extrato', detail: 'Entradas e saídas dos últimos 30 dias.' },
  { code: 'PAYMENTS', title: 'Trazer dinheiro', detail: 'Mandar dinheiro de lá para cá, quando você pedir.' },
]

const STATUS: Record<Consent['status'], string> = {
  AWAITING: 'Aguardando autorização',
  AUTHORIZED: 'Conectado',
  REJECTED: 'Recusado no outro banco',
  REVOKED: 'Revogado',
  EXPIRED: 'Venceu',
}

const day = new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: 'long', year: 'numeric' })
const short = new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: 'short' })

export function Banks() {
  const [banks, setBanks] = useState<ExternalBank[]>([])
  const [consents, setConsents] = useState<Consent[]>([])
  const [connecting, setConnecting] = useState(false)
  const [authorizing, setAuthorizing] = useState<Consent>()
  const [error, setError] = useState('')

  const load = () => fetchConsents().then(setConsents, (failure) => setError(messageOf(failure)))
  useEffect(() => {
    fetchBanks().then(setBanks, () => {})
    void load()
  }, [])

  const live = consents.filter((c) => c.status === 'AUTHORIZED' || c.status === 'AWAITING')
  const past = consents.filter((c) => !live.includes(c))
  const free = banks.filter((b) => !live.some((c) => c.bankCode === b.code))

  if (authorizing) {
    return (
      <OtherBank
        consent={authorizing}
        onDone={() => {
          setAuthorizing(undefined)
          void load()
        }}
      />
    )
  }

  return (
    <section className="banks" aria-labelledby="banks-title">
      <header className="section-head">
        <div>
          <h1 id="banks-title">Outros bancos</h1>
          <p className="muted">Veja suas contas de outros bancos aqui e traga dinheiro de lá. Você escolhe o que compartilhar e pode cortar quando quiser.</p>
        </div>
        {free.length > 0 && !connecting && (
          <button type="button" className="button" onClick={() => setConnecting(true)}>
            Conectar banco
          </button>
        )}
      </header>

      {error && <Alert>{error}</Alert>}
      {connecting && (
        <ConnectForm
          banks={free}
          onCancel={() => setConnecting(false)}
          onAsked={(consent) => {
            setConnecting(false)
            setAuthorizing(consent)
          }}
        />
      )}

      {live.length === 0 && !connecting && (
        <p className="banks-empty">Nenhum banco conectado. Os bancos aqui são fictícios, como o resto do dinheiro.</p>
      )}
      <ul className="consents">
        {live.map((consent) => (
          <ConsentRow key={consent.id} consent={consent} onChange={load} onAuthorize={() => setAuthorizing(consent)} />
        ))}
      </ul>

      {past.length > 0 && (
        <details className="past">
          <summary>Compartilhamentos encerrados</summary>
          <ul>
            {past.map((consent) => (
              <li key={consent.id}>
                <span>{consent.bankName}</span>
                <span className="muted">
                  {STATUS[consent.status]}, pedido em {short.format(new Date(consent.createdAt))}
                </span>
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  )
}

function ConnectForm({ banks, onCancel, onAsked }: { banks: ExternalBank[]; onCancel: () => void; onAsked: (c: Consent) => void }) {
  const [bank, setBank] = useState(banks[0]?.code ?? '')
  const [permissions, setPermissions] = useState<Permission[]>(['BALANCES', 'TRANSACTIONS', 'PAYMENTS'])
  const [months, setMonths] = useState(12)
  const [error, setError] = useState('')

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (permissions.length === 0) return setError('Escolha pelo menos um dado.')
    try {
      onAsked(await requestConsent(bank, permissions, months))
    } catch (failure) {
      setError(messageOf(failure))
    }
  }

  return (
    <form className="connect" onSubmit={(event) => void submit(event)}>
      <fieldset className="choice">
        <legend>Banco</legend>
        {banks.map((item) => (
          <label key={item.code} data-checked={bank === item.code}>
            <input type="radio" name="bank" checked={bank === item.code} onChange={() => setBank(item.code)} />
            {item.name}
          </label>
        ))}
      </fieldset>
      <fieldset className="permissions">
        <legend>O que compartilhar</legend>
        {PERMISSIONS.map((item) => (
          <label key={item.code} className="check">
            <input
              type="checkbox"
              checked={permissions.includes(item.code)}
              onChange={(event) =>
                setPermissions((current) =>
                  event.target.checked ? [...current, item.code] : current.filter((p) => p !== item.code),
                )
              }
            />
            <span>
              <strong>{item.title}</strong>
              <span className="muted"> {item.detail}</span>
            </span>
          </label>
        ))}
      </fieldset>
      <fieldset className="choice">
        <legend>Por quanto tempo</legend>
        {[3, 6, 12].map((value) => (
          <label key={value} data-checked={months === value}>
            <input type="radio" name="months" checked={months === value} onChange={() => setMonths(value)} />
            {value} meses
          </label>
        ))}
      </fieldset>
      {error && <Alert>{error}</Alert>}
      <div className="connect-actions">
        <button type="button" className="button quiet" onClick={onCancel}>
          Cancelar
        </button>
        <button type="submit" className="button">
          Continuar no banco
        </button>
      </div>
    </form>
  )
}

/** A tela do outro banco, simulada: no Open Finance de verdade, o cliente autoriza no app de lá. */
function OtherBank({ consent, onDone }: { consent: Consent; onDone: () => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const title = useRef<HTMLHeadingElement>(null)
  useEffect(() => title.current?.focus(), [])

  async function decide(approve: boolean) {
    setBusy(true)
    try {
      await authorizeConsent(consent.id, approve)
      onDone()
    } catch (failure) {
      setError(messageOf(failure))
      setBusy(false)
    }
  }

  return (
    <section className="other-bank" data-bank={consent.bankCode} aria-labelledby="other-title">
      <p className="other-bar">Você saiu do Lastro e está no {consent.bankName}</p>
      <div className="other-body">
        <h1 id="other-title" ref={title} tabIndex={-1}>
          {consent.bankName}
        </h1>
        <p>O Lastro pede para ver e usar estes dados da sua conta por {consent.months} meses:</p>
        <ul>
          {consent.permissions.map((code) => (
            <li key={code}>{PERMISSIONS.find((p) => p.code === code)?.title}</li>
          ))}
        </ul>
        <p className="muted">Você pode cancelar a qualquer momento, aqui ou no Lastro.</p>
        {error && <Alert>{error}</Alert>}
        <div className="other-actions">
          <button type="button" className="other-no" disabled={busy} onClick={() => void decide(false)}>
            Recusar
          </button>
          <button type="button" className="other-yes" disabled={busy} onClick={() => void decide(true)}>
            Autorizar e voltar ao Lastro
          </button>
        </div>
      </div>
    </section>
  )
}

function ConsentRow({ consent, onChange, onAuthorize }: { consent: Consent; onChange: () => void; onAuthorize: () => void }) {
  const { money } = usePrivacy()
  const { reload } = useBank()
  const [items, setItems] = useState<ExternalTransaction[]>()
  const [bringing, setBringing] = useState(false)
  const [cents, setCents] = useState(0)
  const [key, setKey] = useState(() => crypto.randomUUID())
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')

  async function bring(event: FormEvent) {
    event.preventDefault()
    if (cents === 0) return setError('Digite o valor.')
    setError('')
    try {
      const transfer = await bringMoney(consent.id, cents, key)
      setMessage(`${formatCents(transfer.amountCents)} chegaram na sua conta do Lastro.`)
      setBringing(false)
      setCents(0)
      setKey(crypto.randomUUID())
      setItems(undefined)
      onChange()
      void reload()
    } catch (failure) {
      setError(messageOf(failure))
    }
  }

  async function revoke() {
    setError('')
    try {
      await revokeConsent(consent.id)
      onChange()
    } catch (failure) {
      setError(messageOf(failure))
    }
  }

  const can = (p: Permission) => consent.status === 'AUTHORIZED' && consent.permissions.includes(p)

  return (
    <li className="consent" data-status={consent.status}>
      <div className="consent-head">
        <div>
          <strong className="consent-bank">{consent.bankName}</strong>
          <span className="muted">
            {STATUS[consent.status]}
            {consent.account && `, agência ${consent.account.branch}, conta ${consent.account.number}`}
          </span>
          {consent.expiresAt && consent.status === 'AUTHORIZED' && (
            <span className="muted">Vale até {day.format(new Date(consent.expiresAt))}</span>
          )}
        </div>
        {consent.account?.balanceCents != null && <p className="consent-balance">{money(consent.account.balanceCents)}</p>}
      </div>

      <div className="consent-actions">
        {consent.status === 'AWAITING' && (
          <button type="button" className="button" onClick={onAuthorize}>
            Autorizar no {consent.bankName}
          </button>
        )}
        {can('PAYMENTS') && !bringing && (
          <button type="button" className="button" onClick={() => setBringing(true)}>
            Trazer dinheiro
          </button>
        )}
        {can('TRANSACTIONS') && !items && (
          <button
            type="button"
            className="button quiet"
            onClick={() => fetchExternalTransactions(consent.id).then(setItems, (failure) => setError(messageOf(failure)))}
          >
            Ver extrato de lá
          </button>
        )}
        <button type="button" className="link-button" onClick={() => void revoke()}>
          {consent.status === 'AWAITING' ? 'Cancelar pedido' : 'Revogar acesso'}
        </button>
      </div>

      {bringing && (
        <form className="bring" onSubmit={(event) => void bring(event)}>
          <label className="field">
            <span>Quanto trazer</span>
            <input className="money-input" inputMode="numeric" value={formatCents(cents)} autoFocus
              onChange={(event) => setCents(centsFromInput(event.target.value))} />
          </label>
          <button type="submit" className="button">
            Trazer {cents > 0 ? formatCents(cents) : ''}
          </button>
          <button type="button" className="link-button" onClick={() => setBringing(false)}>
            Cancelar
          </button>
        </form>
      )}
      {error && <Alert>{error}</Alert>}
      <p className="notice" role="status">
        {message}
      </p>

      {items && (
        <ul className="external-list">
          {items.map((item, index) => (
            <li key={index}>
              <span>
                {item.description}
                <small className="muted">{short.format(new Date(item.at))}</small>
              </span>
              <strong data-sign={item.amountCents > 0 ? 'in' : 'out'}>
                {item.amountCents > 0 ? '+ ' : '- '}
                {money(Math.abs(item.amountCents))}
              </strong>
            </li>
          ))}
        </ul>
      )}
    </li>
  )
}
