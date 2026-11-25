import { useEffect, useRef, useState, type PointerEvent } from 'react'
import type { Entry } from '../lib/api'
import { usePrivacy } from '../lib/privacy'
import { EntryRow, formatDate } from './EntryRow'

const HEIGHT = 190
const GUTTER = 15
const TOP = 16
const BOTTOM = 30

/**
 * Linha de lastro: o saldo caminha da esquerda até hoje; em "hoje" a linha vira e desce pelos
 * lançamentos, do mais novo ao mais antigo. Em degraus, porque saldo não muda aos poucos.
 */
export function BallastLine({ entries, onTrace }: { entries: Entry[]; onTrace: (entry: Entry) => void }) {
  const { money } = usePrivacy()
  const box = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)
  const [active, setActive] = useState<number | null>(null)

  useEffect(() => {
    const element = box.current
    if (!element) return
    const observer = new ResizeObserver(([entry]) => setWidth(entry!.contentRect.width))
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  const oldestFirst = [...entries].reverse()
  const balances = oldestFirst.map((entry) => entry.balanceAfterCents)
  const first = oldestFirst[0]
  const opening = first
    ? first.balanceAfterCents - (first.direction === 'CREDIT' ? first.amountCents : -first.amountCents)
    : 0
  const top = Math.max(opening, ...balances, 1)
  const end = Math.max(width - GUTTER, 1)
  const step = end / Math.max(balances.length, 1)
  // A base é sempre zero, para a altura não exagerar a variação.
  const y = (cents: number) => TOP + (1 - cents / top) * (HEIGHT - TOP - BOTTOM)
  const x = (index: number) => step * (index + 0.5)

  let path = `M0 ${y(opening)}`
  balances.forEach((cents, i) => {
    path += `H${x(i)}V${y(cents)}`
  })
  const floor = HEIGHT - BOTTOM
  path += `H${end}`
  const area = `${path}V${floor}H0Z`
  const line = `${path}V${HEIGHT}`
  const selected = active === null ? undefined : oldestFirst[active]

  const track = (event: PointerEvent<SVGSVGElement>) => {
    const position = event.clientX - event.currentTarget.getBoundingClientRect().left
    setActive(Math.min(Math.max(Math.round(position / step - 0.5), 0), balances.length - 1))
  }

  return (
    <div className="ballast">
      <div ref={box} className="ballast-chart">
        {width > 0 && (
          <svg
            width={width}
            height={HEIGHT}
            role="img"
            aria-label={`Saldo depois de cada um dos últimos ${entries.length} lançamentos, listados abaixo`}
            onPointerMove={track}
            onPointerLeave={() => setActive(null)}
          >
            <line className="ballast-floor" x1="0" x2={end} y1={floor} y2={floor} />
            <path className="ballast-area" d={area} />
            <path className="ballast-path" d={line} />
            {selected && active !== null && (
              <circle className="ballast-node" cx={x(active)} cy={y(selected.balanceAfterCents)} r="6" />
            )}
          </svg>
        )}
        <p className="ballast-caption" aria-hidden="true">
          {selected
            ? `${money(selected.balanceAfterCents)} em ${formatDate(selected.createdAt)}`
            : 'Saldo após cada lançamento'}
        </p>
      </div>
      <ol className="entries on-line">
        {entries.map((entry, i) => (
          <EntryRow key={entry.entryId} entry={entry} onTrace={onTrace} active={active === entries.length - 1 - i} />
        ))}
      </ol>
    </div>
  )
}
