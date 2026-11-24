import { useEffect, useRef, useState, type FormEvent } from 'react'
import { deposit } from '../lib/api'
import { centsFromInput, formatCents } from '../lib/money'
import { messageOf } from '../lib/session'
import { Alert, Field } from './ui'

const LIMIT = 5_000_00
const SHORTCUTS = [50_00, 200_00, 500_00]

interface Props {
  accountId: string
  onClose: () => void
  onDone: (amountCents: number) => void
}

export function DepositDialog({ accountId, onClose, onDone }: Props) {
  const dialog = useRef<HTMLDialogElement>(null)
  // A chave acompanha o valor: repetir após uma falha de rede não deposita duas vezes.
  const key = useRef(crypto.randomUUID())
  const [cents, setCents] = useState(0)
  const [error, setError] = useState('')
  const [sending, setSending] = useState(false)

  useEffect(() => {
    dialog.current?.showModal()
  }, [])

  const change = (value: number) => {
    if (value !== cents) key.current = crypto.randomUUID()
    setCents(value)
    setError('')
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (cents === 0) return setError('Digite um valor para depositar.')
    if (cents > LIMIT) return setError(`O limite é de ${formatCents(LIMIT)} por dia.`)
    setSending(true)
    try {
      await deposit(accountId, cents, key.current)
      onDone(cents)
    } catch (failure) {
      setError(messageOf(failure))
      setSending(false)
    }
  }

  return (
    <dialog ref={dialog} className="dialog" aria-labelledby="deposit-title" onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <h2 id="deposit-title">Depositar</h2>
        <p className="muted">O dinheiro é fictício. O limite é de {formatCents(LIMIT)} por dia.</p>
        <Field
          label="Valor"
          className="money-input"
          inputMode="numeric"
          autoComplete="off"
          autoFocus
          value={formatCents(cents)}
          onChange={(event) => change(centsFromInput(event.target.value))}
        />
        <div className="shortcuts">
          {SHORTCUTS.map((value) => (
            <button type="button" key={value} className="button quiet" onClick={() => change(value)}>
              {formatCents(value)}
            </button>
          ))}
        </div>
        {error && <Alert>{error}</Alert>}
        <div className="dialog-actions">
          <button type="button" className="button quiet" onClick={() => dialog.current?.close()}>
            Cancelar
          </button>
          <button className="button" disabled={sending}>
            {sending ? 'Depositando…' : 'Depositar'}
          </button>
        </div>
      </form>
    </dialog>
  )
}
