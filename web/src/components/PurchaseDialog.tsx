import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { buy, fetchMerchants, type Card, type Merchant, type Purchase } from '../lib/api'
import { centsFromInput, formatCents } from '../lib/money'
import { messageOf } from '../lib/session'
import { TapScene, type TapDevice } from './TapScene'
import { Alert, Field } from './ui'

interface Props {
  card: Card
  onClose: () => void
  onDone: (purchase: Purchase) => void
}

/** Compra de teste em lojas inventadas, para ver débito, crédito e parcelas funcionando. */
export function PurchaseDialog({ card, onClose, onDone }: Props) {
  const dialog = useRef<HTMLDialogElement>(null)
  const ids = { merchant: useId(), installments: useId() }
  const key = useRef(crypto.randomUUID())
  const [merchants, setMerchants] = useState<Merchant[]>([])
  const [merchant, setMerchant] = useState('')
  const [cents, setCents] = useState(0)
  const [mode, setMode] = useState<'DEBIT' | 'CREDIT'>('CREDIT')
  const [installments, setInstallments] = useState(1)
  // INSERT: cartão na maquininha, com senha. Os outros são por aproximação.
  const [how, setHow] = useState<'INSERT' | TapDevice>('INSERT')
  const contactless = how !== 'INSERT'
  const [approved, setApproved] = useState<Purchase>()
  const [error, setError] = useState('')
  const [sending, setSending] = useState(false)

  useEffect(() => {
    dialog.current?.showModal()
    fetchMerchants().then((list) => {
      setMerchants(list)
      setMerchant(list[0]?.code ?? '')
    }, (failure) => setError(messageOf(failure)))
  }, [])

  const changed = () => {
    key.current = crypto.randomUUID()
    setError('')
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (cents === 0) return setError('Digite o valor da compra.')
    setSending(true)
    try {
      const purchase = await buy(card.id, { merchant, amountCents: cents, mode, installments: mode === 'DEBIT' ? 1 : installments, contactless, ...(contactless ? { device: how } : {}) }, key.current)
      setApproved(purchase)
      onDone(purchase)
    } catch (failure) {
      setError(messageOf(failure))
      setSending(false)
    }
  }

  return (
    <dialog ref={dialog} className="dialog" aria-labelledby="buy-title" onClose={onClose}>
      {approved ? (
        <div className="approved">
          {approved.device ? <TapScene device={approved.device} /> : <span className="tap" aria-hidden="true" />}
          <h2 id="buy-title">Compra aprovada</h2>
          <p>
            {approved.merchant}, {formatCents(approved.amountCents)} no {approved.mode === 'DEBIT' ? 'débito' : 'crédito'}
            {approved.installments > 1 && ` em ${approved.installments}x`}
            {approved.device && `, aproximando o ${{ CARD: 'cartão', PHONE: 'celular', WATCH: 'relógio' }[approved.device]}`}.
          </p>
          {approved.discountCents > 0 && (
            <p className="coupon-held">Cupom da loja: {formatCents(approved.discountCents)} de desconto</p>
          )}
          <div className="dialog-actions">
            <button type="button" className="button" autoFocus onClick={() => dialog.current?.close()}>
              Fechar
            </button>
          </div>
        </div>
      ) : (
        <form onSubmit={submit} noValidate>
          <h2 id="buy-title">Simular compra</h2>
          <p className="muted">Cartão {card.label}, final {card.last4}. Lojas inventadas, dinheiro fictício.</p>
          <div className="field">
            <label htmlFor={ids.merchant}>Loja</label>
            <select id={ids.merchant} value={merchant} onChange={(event) => { changed(); setMerchant(event.target.value) }}>
              {merchants.map((item) => (
                <option key={item.code} value={item.code}>
                  {item.name}, {item.category.toLowerCase()}
                </option>
              ))}
            </select>
          </div>
          <Field label="Valor" className="money-input" inputMode="numeric" autoComplete="off" autoFocus
            value={formatCents(cents)} onChange={(event) => { changed(); setCents(centsFromInput(event.target.value)) }} />
          <div className="segmented" role="radiogroup" aria-label="Forma de pagamento">
            {(['CREDIT', 'DEBIT'] as const).map((value) => (
              <button key={value} type="button" role="radio" aria-checked={mode === value}
                onClick={() => { changed(); setMode(value) }}>
                {value === 'CREDIT' ? 'Crédito' : 'Débito'}
              </button>
            ))}
          </div>
          {mode === 'CREDIT' && (
            <div className="field">
              <label htmlFor={ids.installments}>Parcelas</label>
              <select id={ids.installments} value={installments} onChange={(event) => { changed(); setInstallments(Number(event.target.value)) }}>
                {Array.from({ length: 12 }, (_, i) => i + 1).map((n) => (
                  <option key={n} value={n}>
                    {n === 1 ? 'À vista' : `${n}x de ${formatCents(Math.floor(cents / n))}`}
                  </option>
                ))}
              </select>
            </div>
          )}
          <fieldset className="choice">
            <legend>Na maquininha</legend>
            {([['INSERT', 'Inserir cartão'], ['CARD', 'Aproximar cartão'], ['PHONE', 'Celular'], ['WATCH', 'Relógio']] as const).map(([value, label]) => (
              <label key={value} data-checked={how === value}>
                <input type="radio" name="how" checked={how === value} onChange={() => { changed(); setHow(value) }} />
                {label}
              </label>
            ))}
          </fieldset>
          {error && <Alert>{error}</Alert>}
          <div className="dialog-actions">
            <button type="button" className="button quiet" onClick={() => dialog.current?.close()}>
              Cancelar
            </button>
            <button className="button" disabled={sending || !merchant}>
              {sending ? 'Aprovando…' : 'Comprar'}
            </button>
          </div>
        </form>
      )}
    </dialog>
  )
}
