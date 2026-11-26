import { useState } from 'react'
import { PurchaseDialog } from '../components/PurchaseDialog'
import { Alert, Switch } from '../components/ui'
import { formatDate } from '../components/EntryRow'
import { fetchPurchases, type Purchase } from '../lib/api'
import { usePrivacy } from '../lib/privacy'
import { Link } from 'react-router'
import { useEffect } from 'react'
import { Wallet } from '../components/Wallet'
import type { CardChange } from '../lib/api'
import { wrap } from '../lib/carousel'
import { messageOf } from '../lib/session'
import { useBank } from './AppShell'

export function Cards() {
  const { cards, changeCard } = useBank()
  const [position, setPosition] = useState(() => Math.max(cards.findIndex((card) => card.primary), 0))
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [buying, setBuying] = useState(false)
  const [purchases, setPurchases] = useState<Purchase[]>()
  const { money } = usePrivacy()

  useEffect(() => {
    fetchPurchases().then(setPurchases, () => setPurchases([]))
  }, [])

  const card = cards[wrap(position, cards.length)]
  if (!card) return <p className="state muted">Você ainda não tem cartões.</p>

  const change = async (what: CardChange) => {
    setSaving(true)
    setError('')
    try {
      await changeCard(card.id, what)
    } catch (failure) {
      setError(messageOf(failure))
    }
    setSaving(false)
  }

  return (
    <div className="cards-page">
      <h1>Cartões</h1>
      <Wallet cards={cards} position={position} onChange={setPosition} />
      <section className="card-settings" aria-label={`Ajustes do cartão ${card.label}`}>
        {card.primary ? (
          <p className="primary-note">Este é o seu cartão principal.</p>
        ) : (
          <button type="button" className="button wide" disabled={saving} onClick={() => change({ primary: true })}>
            Usar como principal
          </button>
        )}
        <Switch
          label="Bloquear cartão"
          hint="Você desbloqueia quando quiser."
          checked={card.frozen}
          disabled={saving}
          onChange={(frozen) => change({ frozen })}
        />
        <Switch
          label="Aproximação"
          hint="Permite pagar encostando o cartão."
          checked={card.contactless}
          disabled={saving}
          onChange={(contactless) => change({ contactless })}
        />
        {error && <Alert>{error}</Alert>}
        <div className="actions card-links">
          <button type="button" className="button quiet" disabled={card.frozen} onClick={() => setBuying(true)}>
            Simular compra
          </button>
          <Link className="button quiet" to="/app/credito">
            Fatura e limite
          </Link>
        </div>
      </section>
      <section className="purchases" aria-labelledby="purchases-title">
        <h2 id="purchases-title" className="section-title">Compras deste cartão</h2>
        {purchases?.filter((item) => item.cardId === card.id).length === 0 && (
          <p className="muted">Nenhuma compra ainda. Use “Simular compra” para testar débito, crédito e parcelas.</p>
        )}
        <ul>
          {purchases?.filter((item) => item.cardId === card.id).map((item) => (
            <li key={item.id}>
              <span>
                <strong>{item.merchant}</strong>
                <span className="muted">
                  {formatDate(item.createdAt)}, {item.mode === 'DEBIT' ? 'débito' : item.installments > 1 ? `crédito em ${item.installments}x` : 'crédito'}
                  {item.contactless && ', aproximação'}
                </span>
              </span>
              <strong>{money(item.amountCents)}</strong>
            </li>
          ))}
        </ul>
      </section>
      {buying && (
        <PurchaseDialog card={card} onClose={() => setBuying(false)}
          onDone={(purchase) => setPurchases((current) => [purchase, ...(current ?? [])])} />
      )}
    </div>
  )
}
