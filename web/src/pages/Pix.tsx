import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { formatDate } from '../components/EntryRow'
import { Alert, Field } from '../components/ui'
import {
  createPixKey,
  fetchPixStatus,
  deletePixKey,
  fetchPixContacts,
  fetchPixKeys,
  fetchPixLimits,
  lookupPixKey,
  sendPix,
  type PixContact,
  type PixKey,
  type PixKeyKind,
  type PixLimits,
  type PixReceipt,
  type PixRecipient,
} from '../lib/api'
import { formatCpf } from '../lib/cpf'
import { centsFromInput, formatCents } from '../lib/money'
import { usePrivacy } from '../lib/privacy'
import { messageOf } from '../lib/session'
import { useBank } from './AppShell'

const KIND_LABEL: Record<PixKeyKind, string> = { EMAIL: 'E-mail', CPF: 'CPF', RANDOM: 'Chave aleatória' }

export function Pix() {
  const { account, reload } = useBank()
  const { money } = usePrivacy()
  const [limits, setLimits] = useState<PixLimits>()
  const [contacts, setContacts] = useState<PixContact[]>([])
  const [recipient, setRecipient] = useState<PixRecipient>()
  const [receipt, setReceipt] = useState<PixReceipt>()

  const refresh = useCallback(() => {
    // Limite e contatos são apoio: sem eles ainda dá para enviar, e o servidor confere tudo de novo.
    fetchPixLimits().then(setLimits, () => {})
    fetchPixContacts().then(setContacts, () => {})
  }, [])

  useEffect(refresh, [refresh])

  const restart = () => {
    setRecipient(undefined)
    setReceipt(undefined)
  }

  return (
    <div className="pix">
      <div className="section-head">
        <h1>Pix</h1>
        <p className="muted">Entre contas do Lastro, com dinheiro fictício</p>
      </div>

      <section className="pix-send" aria-label="Enviar">
        {receipt ? (
          <ReceiptView receipt={receipt} onAgain={restart} />
        ) : recipient ? (
          <AmountStep
            recipient={recipient}
            available={account.availableCents}
            limits={limits}
            onBack={restart}
            onSent={(result) => {
              setReceipt(result)
              void reload()
              refresh()
            }}
          />
        ) : (
          <KeyStep contacts={contacts} onFound={setRecipient} />
        )}
        <dl className="pix-facts">
          <div>
            <dt>Saldo</dt>
            <dd>{money(account.availableCents)}</dd>
          </div>
          {limits && (
            <div>
              <dt>{limits.night ? 'Limite desta noite' : 'Limite de hoje'}</dt>
              <dd>{money(limits.availableCents)}</dd>
            </div>
          )}
        </dl>
      </section>

      <Keys />
    </div>
  )
}

function KeyStep({ contacts, onFound }: { contacts: PixContact[]; onFound: (recipient: PixRecipient) => void }) {
  const [key, setKey] = useState('')
  const [error, setError] = useState('')
  const [searching, setSearching] = useState(false)

  const search = async (value: string) => {
    if (value.trim() === '') return setError('Digite a chave de quem vai receber.')
    setError('')
    setSearching(true)
    try {
      onFound(await lookupPixKey(value))
    } catch (failure) {
      setError(messageOf(failure))
      setSearching(false)
    }
  }

  return (
    <form
      className="pix-step"
      noValidate
      onSubmit={(event) => {
        event.preventDefault()
        void search(key)
      }}
    >
      <Field
        label="Chave de quem vai receber"
        hint="E-mail, CPF ou chave aleatória."
        autoComplete="off"
        autoCapitalize="none"
        spellCheck={false}
        maxLength={254}
        value={key}
        onChange={(event) => setKey(event.target.value)}
      />
      {error && <Alert>{error}</Alert>}
      <button className="button large" disabled={searching}>
        {searching ? 'Procurando…' : 'Continuar'}
      </button>
      {contacts.length > 0 && (
        <div className="pix-contacts">
          <h2>Recentes</h2>
          <ul>
            {contacts.map((contact) => (
              <li key={contact.key}>
                <button type="button" disabled={searching} onClick={() => void search(contact.key)}>
                  <span className="initials" aria-hidden="true">
                    {initials(contact.name)}
                  </span>
                  {contact.name}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </form>
  )
}

interface AmountProps {
  recipient: PixRecipient
  available: number
  limits: PixLimits | undefined
  onBack: () => void
  onSent: (receipt: PixReceipt) => void
}

function AmountStep({ recipient, available, limits, onBack, onSent }: AmountProps) {
  const [cents, setCents] = useState(0)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [sending, setSending] = useState(false)
  // A chave acompanha o conteúdo: repetir após uma queda de conexão não envia duas vezes.
  const idempotency = useRef(crypto.randomUUID())

  const changed = () => {
    idempotency.current = crypto.randomUUID()
    setError('')
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (cents === 0) return setError('Digite o valor do Pix.')
    if (cents > available) return setError('Seu saldo não cobre este valor.')
    if (limits && cents > limits.availableCents) {
      return setError(`Seu limite ${limits.night ? 'desta noite' : 'de hoje'} é ${formatCents(limits.availableCents)}.`)
    }
    setSending(true)
    try {
      onSent(await sendPix(recipient.key, cents, message, idempotency.current))
    } catch (failure) {
      setError(messageOf(failure))
      setSending(false)
    }
  }

  return (
    <form className="pix-step" onSubmit={submit} noValidate>
      <div className="pix-person">
        <span className="initials" aria-hidden="true">
          {initials(recipient.name)}
        </span>
        <div>
          <strong>{recipient.name}</strong>
          <span className="muted">CPF {recipient.maskedCpf}</span>
        </div>
      </div>
      <Field
        label="Valor"
        className="money-input"
        inputMode="numeric"
        autoComplete="off"
        autoFocus
        value={formatCents(cents)}
        onChange={(event) => {
          changed()
          setCents(centsFromInput(event.target.value))
        }}
      />
      <Field
        label="Mensagem"
        hint="Opcional."
        autoComplete="off"
        maxLength={80}
        value={message}
        onChange={(event) => {
          changed()
          setMessage(event.target.value)
        }}
      />
      {error && <Alert>{error}</Alert>}
      <div className="actions">
        <button className="button large" disabled={sending}>
          {sending ? 'Enviando…' : cents > 0 ? `Enviar ${formatCents(cents)}` : 'Enviar'}
        </button>
        <button type="button" className="button quiet large" disabled={sending} onClick={onBack}>
          Voltar
        </button>
      </div>
    </form>
  )
}

const STATUS_TEXT: Record<PixReceipt['status'], { title: string; detail: string }> = {
  PENDING: { title: 'Pix em análise', detail: 'O antifraude está conferindo. Costuma levar segundos; o valor fica reservado.' },
  UNDER_REVIEW: { title: 'Pix em revisão', detail: 'Uma pessoa vai conferir este Pix. O valor fica reservado até a decisão, no máximo por 24 horas.' },
  SETTLED: { title: 'Pix enviado', detail: '' },
  DENIED: { title: 'Pix não enviado', detail: 'A análise de segurança barrou este Pix. O valor voltou para o seu saldo.' },
  CANCELLED: { title: 'Pix cancelado', detail: 'A análise não terminou a tempo. O valor voltou para o seu saldo.' },
}

function ReceiptView({ receipt: first, onAgain }: { receipt: PixReceipt; onAgain: () => void }) {
  const { money } = usePrivacy()
  const { reload } = useBank()
  const title = useRef<HTMLHeadingElement>(null)
  const [receipt, setReceipt] = useState(first)
  useEffect(() => title.current?.focus(), [])

  // Acompanha a análise; para quando há decisão ou depois de um minuto.
  useEffect(() => {
    if (receipt.status !== 'PENDING') return
    let tries = 0
    const timer = setInterval(() => {
      tries++
      fetchPixStatus(receipt.id).then((next) => {
        if (next.status !== 'PENDING') {
          setReceipt(next)
          void reload()
        }
      }, () => {})
      if (tries >= 40) clearInterval(timer)
    }, 1500)
    return () => clearInterval(timer)
  }, [receipt.id, receipt.status, reload])

  const text = STATUS_TEXT[receipt.status]
  return (
    <div className="pix-receipt" data-status={receipt.status}>
      <svg className="pix-swim" viewBox="0 0 320 90" aria-hidden="true">
        <path d="M6 64C70 8 130 92 190 44S288 22 314 34" />
        <g className="pix-fish">
          <path className="fish-tail" d="M-13 0l-11-8v16z" />
          <circle className="fish-body" r="13" />
          <circle className="pix-eye" cx="5" cy="-4" r="2.500" />
        </g>
      </svg>
      <h2 ref={title} tabIndex={-1} aria-live="polite">
        {text.title}
      </h2>
      <p className="balance">{money(receipt.amountCents)}</p>
      {text.detail && <p className="receipt-detail">{text.detail}</p>}
      <dl>
        <div>
          <dt>Para</dt>
          <dd>{receipt.recipientName}</dd>
        </div>
        <div>
          <dt>Quando</dt>
          <dd>{formatDate(receipt.createdAt)}</dd>
        </div>
        {receipt.message && (
          <div>
            <dt>Mensagem</dt>
            <dd>{receipt.message}</dd>
          </div>
        )}
        <div>
          <dt>Comprovante</dt>
          <dd className="code">{receipt.id}</dd>
        </div>
      </dl>
      <div className="actions">
        <button type="button" className="button" onClick={onAgain}>
          Fazer outro Pix
        </button>
        <Link className="button quiet" to="/app">
          Ver movimentações
        </Link>
      </div>
    </div>
  )
}

function Keys() {
  const [keys, setKeys] = useState<PixKey[]>()
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [removing, setRemoving] = useState<string>()

  const load = useCallback(async () => {
    setError('')
    try {
      setKeys(await fetchPixKeys())
    } catch (failure) {
      setError(messageOf(failure))
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const run = async (action: () => Promise<unknown>, done: string) => {
    setBusy(true)
    setError('')
    setNotice('')
    try {
      await action()
      await load()
      setNotice(done)
    } catch (failure) {
      setError(messageOf(failure))
    }
    setRemoving(undefined)
    setBusy(false)
  }

  const copy = async (value: string) => {
    try {
      await navigator.clipboard.writeText(value)
      setNotice('Chave copiada.')
    } catch {
      setNotice('Não deu para copiar. Selecione a chave e copie.')
    }
  }

  const has = (kind: PixKeyKind) => keys?.some((key) => key.kind === kind)
  const options = (['EMAIL', 'CPF', 'RANDOM'] as const).filter((kind) => kind === 'RANDOM' || !has(kind))

  return (
    <section className="pix-keys" aria-labelledby="keys-title">
      <h2 id="keys-title">Suas chaves</h2>
      {keys?.length === 0 && <p className="muted">Cadastre uma chave para receber Pix de outras contas.</p>}
      {!keys && !error && (
        <p className="muted" role="status">
          Carregando chaves…
        </p>
      )}
      {keys && keys.length > 0 && (
        <ul>
          {keys.map((key) => (
            <li key={key.id}>
              <div>
                <span className="muted">{KIND_LABEL[key.kind]}</span>
                <span className="code">{key.kind === 'CPF' ? formatCpf(key.value) : key.value}</span>
              </div>
              {removing === key.id ? (
                <div className="actions">
                  <button type="button" className="button" disabled={busy}
                    onClick={() => void run(() => deletePixKey(key.id), 'Chave excluída.')}>
                    Excluir
                  </button>
                  <button type="button" className="button quiet" onClick={() => setRemoving(undefined)}>
                    Manter
                  </button>
                </div>
              ) : (
                <div className="actions">
                  <button type="button" className="link-button" onClick={() => void copy(key.value)}>
                    Copiar
                  </button>
                  <button type="button" className="link-button" onClick={() => setRemoving(key.id)}>
                    Excluir
                  </button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      {error && <Alert>{error}</Alert>}
      <p className="notice" role="status">
        {notice}
      </p>
      {keys && (
        <div className="actions">
          {options.map((kind) => (
            <button type="button" key={kind} className="button quiet" disabled={busy}
              onClick={() => void run(() => createPixKey(kind), 'Chave cadastrada.')}>
              Cadastrar {kind === 'RANDOM' ? 'chave aleatória' : KIND_LABEL[kind]}
            </button>
          ))}
        </div>
      )}
    </section>
  )
}

function initials(name: string) {
  const words = name.trim().split(/\s+/)
  return (words[0]![0]! + (words.length > 1 ? words.at(-1)![0]! : '')).toUpperCase()
}
