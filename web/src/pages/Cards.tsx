import { useState } from 'react'
import { Alert, Switch } from '../components/ui'
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
      </section>
    </div>
  )
}
