import { type CSSProperties, type PointerEvent } from 'react'
import { Link } from 'react-router'
import { ICONS } from '../pages/AppShell'

export interface Tile {
  to: string
  name: string
  hint: string
  icon: string
  /** O Pix é a ação principal: ocupa mais espaço e fica escuro. */
  main?: boolean
}

export const TILES: Tile[] = [
  { to: '/app/pix', name: 'Pix', hint: 'Mandar e receber na hora', icon: ICONS.pix, main: true },
  { to: '/app/cartoes', name: 'Cartões', hint: 'Bloquear e escolher o principal', icon: ICONS.cards },
  { to: '/app/caixinhas', name: 'Caixinhas', hint: 'Guardar rendendo CDI', icon: ICONS.vaults },
  { to: '/app/investir', name: 'Investir', hint: 'Bitcoin, dólar e euro', icon: ICONS.invest },
  { to: '/app/credito', name: 'Crédito', hint: 'Fatura e limite', icon: ICONS.credit },
  { to: '/app/emprestimo', name: 'Empréstimo', hint: 'Simular e contratar', icon: 'M4 12h16M12 4v16M6 6l12 12' },
  { to: '/app/extrato', name: 'Extrato', hint: 'Tudo que entrou e saiu', icon: ICONS.statement },
  { to: '/app/pagar', name: 'Pagar', hint: 'Boleto e recarga de celular', icon: ICONS.bill },
  { to: '/app/conta', name: 'Conta', hint: 'Seus dados e o tema', icon: ICONS.profile },
]

/**
 * Botões de vidro embaçado. O cursor desembaça o vidro por onde passa; tocar solta uma onda a
 * partir do ponto do toque. Tudo por variáveis CSS, sem re-renderizar.
 */
function TileButton({ tile, index }: { tile: Tile; index: number }) {
  const place = (event: PointerEvent<HTMLAnchorElement>) => {
    const box = event.currentTarget.getBoundingClientRect()
    event.currentTarget.style.setProperty('--x', `${event.clientX - box.left}px`)
    event.currentTarget.style.setProperty('--y', `${event.clientY - box.top}px`)
  }

  return (
    <Link
      to={tile.to}
      className="tile"
      data-main={tile.main ?? false}
      style={{ '--i': index } as CSSProperties}
      onPointerMove={place}
      onPointerDown={(event) => {
        place(event)
        const el = event.currentTarget
        // Reinicia a onda mesmo em toques seguidos.
        el.dataset.pressed = 'false'
        void el.offsetWidth
        el.dataset.pressed = 'true'
      }}
    >
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d={tile.icon} pathLength={1} />
      </svg>
      {tile.main && (
        <span className="tile-signal" aria-hidden="true">
          <i />
          <i />
          <i />
        </span>
      )}
      <span className="tile-name">{tile.name}</span>
      <span className="tile-hint">{tile.hint}</span>
    </Link>
  )
}

export function Tiles() {
  return (
    <nav className="tiles" aria-label="O que você quer fazer">
      {TILES.map((tile, index) => (
        <TileButton key={tile.to} tile={tile} index={index} />
      ))}
    </nav>
  )
}
