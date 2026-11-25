import { useRef, useState, type CSSProperties, type KeyboardEvent, type PointerEvent } from 'react'
import { offsetOf, settle, wrap } from '../lib/carousel'
import type { Card } from '../lib/api'
import { BankCard } from './BankCard'

type WalletCard = Pick<Card, 'design' | 'label' | 'last4' | 'frozen' | 'contactless'>

interface Props {
  cards: WalletCard[]
  position: number
  onChange: (position: number) => void
}

/**
 * Carteira: o cartão da frente flutua e os outros esperam atrás. Arrastar, setas do teclado ou os
 * botões trocam; tocar no da frente vira.
 */
export function Wallet({ cards, position, onChange }: Props) {
  const [drag, setDrag] = useState<number | null>(null)
  const [flipped, setFlipped] = useState(false)
  const gesture = useRef({ x: 0, time: 0, lastX: 0, velocity: 0, width: 1, moved: false })

  const count = cards.length
  const front = wrap(Math.round(position), count)

  const go = (next: number) => {
    setFlipped(false)
    onChange(next)
  }

  const down = (event: PointerEvent<HTMLDivElement>) => {
    if (event.button !== 0) return
    gesture.current = {
      x: event.clientX,
      lastX: event.clientX,
      time: event.timeStamp,
      velocity: 0,
      width: event.currentTarget.clientWidth * 0.6,
      moved: false,
    }
    setDrag(0)
  }

  const move = (event: PointerEvent<HTMLDivElement>) => {
    if (drag === null) return
    const g = gesture.current
    const distance = g.x - event.clientX
    // Só captura depois de arrastar; antes disso ainda pode ser um toque para virar.
    if (!g.moved && Math.abs(distance) > 8) {
      g.moved = true
      setFlipped(false)
      event.currentTarget.setPointerCapture(event.pointerId)
    }
    if (!g.moved) return
    const elapsed = Math.max(event.timeStamp - g.time, 1)
    g.velocity = (g.lastX - event.clientX) / elapsed
    g.lastX = event.clientX
    g.time = event.timeStamp
    setDrag(Math.max(-1, Math.min(1, distance / g.width)))
  }

  const up = () => {
    if (drag === null) return
    const g = gesture.current
    if (g.moved) onChange(settle(position, drag, g.velocity))
    setDrag(null)
  }

  const cancel = () => setDrag(null)

  const key = (event: KeyboardEvent) => {
    if (event.key === 'ArrowRight') go(position + 1)
    else if (event.key === 'ArrowLeft') go(position - 1)
  }

  return (
    <div className="wallet" role="group" aria-roledescription="carteira" aria-label="Seus cartões" onKeyDown={key}>
      <div
        className="wallet-stage"
        data-dragging={drag !== null && drag !== 0}
        onPointerDown={down}
        onPointerMove={move}
        onPointerUp={up}
        onPointerCancel={cancel}
      >
        {cards.map((card, i) => {
          const offset = offsetOf(i, position + (drag ?? 0), count)
          const isFront = i === front
          return (
            <div
              key={card.design}
              className="wallet-slot"
              data-front={isFront}
              style={{ '--d': offset, '--a': Math.abs(offset), zIndex: 10 - Math.round(Math.abs(offset) * 5) } as CSSProperties}
            >
              <button
                type="button"
                className="wallet-card"
                tabIndex={isFront ? 0 : -1}
                aria-hidden={!isFront}
                aria-label={`Cartão ${card.label}, final ${card.last4}. ${flipped ? 'Ver a frente' : 'Ver o verso'}`}
                onClick={() => {
                  if (gesture.current.moved) return
                  if (isFront) setFlipped((value) => !value)
                  else go(position + Math.sign(offsetOf(i, position, count)))
                }}
              >
                <BankCard card={card} flipped={isFront && flipped} interactive={isFront && drag === null} />
              </button>
            </div>
          )
        })}
      </div>
      <div className="wallet-controls">
        <button type="button" className="icon-button" aria-label="Cartão anterior" onClick={() => go(position - 1)}>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15 5l-7 7 7 7" /></svg>
        </button>
        <p aria-live="polite">
          {cards[front]!.label} <span className="muted">final {cards[front]!.last4}</span>
        </p>
        <button type="button" className="icon-button" aria-label="Próximo cartão" onClick={() => go(position + 1)}>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 5l7 7-7 7" /></svg>
        </button>
      </div>
    </div>
  )
}
