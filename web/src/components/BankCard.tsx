import type { PointerEvent } from 'react'
import type { Card, CardDesign } from '../lib/api'
import { Wordmark } from './Marks'

const EDGES = [-3, -2, -1, 0, 1, 2]

/** Escamas em fileiras alternadas: a pele do Lino vira a gravura do cartão. */
function Scales({ design }: { design: CardDesign }) {
  const id = `scales-${design}`
  return (
    <svg className="card-scales" aria-hidden="true">
      <defs>
        <pattern id={id} width="44" height="44" patternUnits="userSpaceOnUse">
          {[[22, 0], [0, 22], [44, 22], [22, 44]].map(([x, y]) => (
            <g key={`${x}-${y}`} fill="none" stroke="currentColor">
              <path d={`M${x! - 22} ${y}a22 22 0 0 0 44 0`} />
              <path d={`M${x! - 14} ${y}a14 14 0 0 0 28 0`} opacity="0.55" />
            </g>
          ))}
        </pattern>
      </defs>
      <rect width="100%" height="100%" fill={`url(#${id})`} />
    </svg>
  )
}

type CardFace = Pick<Card, 'design' | 'label' | 'last4' | 'frozen' | 'contactless'>

interface Props {
  card: CardFace
  flipped?: boolean
  /** Inclina conforme o ponteiro passa por cima. */
  interactive?: boolean
}

export function BankCard({ card, flipped = false, interactive = false }: Props) {
  const tilt = (event: PointerEvent<HTMLDivElement>) => {
    if (event.pointerType === 'touch') return
    const box = event.currentTarget.getBoundingClientRect()
    const x = (event.clientX - box.left) / box.width - 0.5
    const y = (event.clientY - box.top) / box.height - 0.5
    const style = event.currentTarget.style
    style.setProperty('--ry', `${x * 22}deg`)
    style.setProperty('--rx', `${y * -18}deg`)
    style.setProperty('--sheen', `${50 + x * 90}%`)
  }
  const rest = (event: PointerEvent<HTMLDivElement>) => {
    for (const name of ['--ry', '--rx', '--sheen']) event.currentTarget.style.removeProperty(name)
  }

  return (
    <div
      className="bank-card"
      data-design={card.design}
      data-frozen={card.frozen}
      data-flipped={flipped}
      onPointerMove={interactive ? tilt : undefined}
      onPointerLeave={interactive ? rest : undefined}
    >
      <div className="card-solid">
        {EDGES.map((z) => (
          <i key={z} className="card-edge" style={{ transform: `translateZ(${z}px)` }} />
        ))}
        <div className="card-face card-front">
          <Scales design={card.design} />
          <div className="card-row">
            <Wordmark />
            <svg className="card-waves" viewBox="0 0 24 24" data-off={!card.contactless} aria-hidden="true">
              <path d="M7 8.500a7 7 0 0 1 0 7M11 6a11 11 0 0 1 0 12M15 3.500a15 15 0 0 1 0 17" />
            </svg>
          </div>
          <svg className="card-chip" viewBox="0 0 46 34" aria-hidden="true">
            <rect x="1" y="1" width="44" height="32" rx="7" />
            <path d="M1 12h14v10H1M45 12H31v10h14M15 1v32M31 1v32" />
          </svg>
          <div className="card-row">
            <span className="card-label">{card.label}</span>
            <span className="card-number">•••• {card.last4}</span>
          </div>
        </div>
        <div className="card-face card-back">
          <Scales design={card.design} />
          <div className="card-stripe" />
          <p>
            Final <strong>{card.last4}</strong>
          </p>
          <p>Cartão de demonstração. Não faz compras reais.</p>
        </div>
        {card.frozen && <span className="card-frozen">Bloqueado</span>}
      </div>
    </div>
  )
}
