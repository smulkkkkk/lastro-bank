import { useEffect, useState, type FormEvent } from 'react'
import { Alert } from '../components/ui'
import { claimCoupon, fetchBubbles, fetchStores, redeemBubbles, type BubbleWallet, type Store } from '../lib/api'
import { formatCents } from '../lib/money'
import { usePrivacy } from '../lib/privacy'
import { messageOf } from '../lib/session'
import { useBank } from './AppShell'

const count = new Intl.NumberFormat('pt-BR')
const until = new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: 'long' })

export function Rewards() {
  const { reload } = useBank()
  const { hidden } = usePrivacy()
  const [wallet, setWallet] = useState<BubbleWallet>()
  const [stores, setStores] = useState<Store[]>([])
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  const load = () => {
    fetchBubbles().then(setWallet, (failure) => setError(messageOf(failure)))
    fetchStores().then(setStores, (failure) => setError(messageOf(failure)))
  }
  useEffect(load, [])

  async function claim(store: Store) {
    setError('')
    setNotice('')
    try {
      await claimCoupon(store.merchant)
      setNotice(`Cupom da ${store.name} guardado. Ele entra sozinho na próxima compra no cartão lá.`)
      load()
    } catch (failure) {
      setError(messageOf(failure))
    }
  }

  return (
    <section className="rewards" aria-labelledby="rewards-title">
      <header className="bubbles-head">
        <span className="bubble-rise" aria-hidden="true">
          <i />
          <i />
          <i />
          <i />
        </span>
        <h1 id="rewards-title" className="balance-label">
          Suas bolhas
        </h1>
        <p className="balance">{wallet ? (hidden ? '•••' : count.format(wallet.bubbles)) : '…'}</p>
        <p className="muted">
          {wallet && !hidden && `Valem ${formatCents(wallet.valueCents)}. `}
          Cada bolha é R$ 0,01 e vem de cashback nas compras no cartão.
        </p>
        {wallet && <RedeemForm max={wallet.bubbles} onDone={(next) => (setWallet(next), void reload())} />}
      </header>

      {error && <Alert>{error}</Alert>}
      <p className="notice" role="status">
        {notice}
      </p>

      <div className="stores">
        <h2>Lojas parceiras</h2>
        <ul>
          {stores.map((store) => (
            <li key={store.merchant} className="store">
              <div className="store-main">
                <strong>{store.name}</strong>
                <span className="muted">{store.blurb}</span>
              </div>
              <div className="store-cashback">
                <span className="store-rate">
                  {String(store.cashbackPercent).replace('.', ',')}
                  <small>%</small>
                </span>
                <span className="muted">em bolhas</span>
              </div>
              <div className="store-coupon">
                <span>
                  {store.couponPercent}% de desconto, até {formatCents(store.couponCapCents)}
                  {store.couponMinCents > 0 && `, em compras a partir de ${formatCents(store.couponMinCents)}`}
                </span>
                {store.coupon ? (
                  <span className="coupon-held">Cupom guardado até {until.format(new Date(store.coupon.expiresAt))}</span>
                ) : (
                  <button type="button" className="button quiet" onClick={() => void claim(store)}>
                    Pegar cupom
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      </div>

      {wallet && wallet.entries.length > 0 && (
        <div className="pay-history">
          <h2>Extrato de bolhas</h2>
          <ul>
            {wallet.entries.map((entry, index) => (
              <li key={index} className="bubble-entry">
                <span>{entry.description}</span>
                <strong data-sign={entry.bubbles > 0 ? 'in' : 'out'}>
                  {entry.bubbles > 0 ? '+' : '−'}
                  {count.format(Math.abs(entry.bubbles))}
                </strong>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  )
}

function RedeemForm({ max, onDone }: { max: number; onDone: (wallet: BubbleWallet) => void }) {
  const [amount, setAmount] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [key, setKey] = useState(() => crypto.randomUUID())
  const bubbles = Number(amount.replace(/\D/g, ''))

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (bubbles < 100) {
      setError('Troque a partir de 100 bolhas.')
      return
    }
    setError('')
    setBusy(true)
    try {
      onDone(await redeemBubbles(bubbles, key))
      setAmount('')
      setKey(crypto.randomUUID())
    } catch (failure) {
      setError(messageOf(failure))
    } finally {
      setBusy(false)
    }
  }

  if (max < 100) {
    return <p className="redeem-hint">Com 100 bolhas, dá para trocar por R$ 1,00 de saldo.</p>
  }
  return (
    <form className="redeem" onSubmit={(event) => void submit(event)}>
      <label className="field">
        <span>Quantas bolhas trocar por saldo</span>
        <input inputMode="numeric" value={amount} placeholder={String(max)} onChange={(event) => setAmount(event.target.value)} />
      </label>
      <button type="button" className="link-button" onClick={() => setAmount(String(max))}>
        Todas
      </button>
      <button type="submit" className="button" disabled={busy}>
        Trocar{bubbles >= 100 ? ` por ${formatCents(bubbles)}` : ''}
      </button>
      {error && <Alert>{error}</Alert>}
    </form>
  )
}
