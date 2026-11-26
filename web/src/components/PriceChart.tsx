import { useEffect, useRef, useState, type PointerEvent } from 'react'
import type { PricePoint } from '../lib/api'
import { formatPrice } from '../lib/invest'
import { pricePath } from '../lib/invest'

const HEIGHT = 170
const day = new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: 'short', timeZone: 'UTC' })
const formatDay = (iso: string) => day.format(new Date(`${iso}T00:00:00Z`)).replace('.', '')

/** Uma série só: o título já diz o que é, então não precisa de legenda. */
export function PriceChart({ points, name }: { points: PricePoint[]; name: string }) {
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

  if (points.length < 2) return <p className="muted">Sem histórico suficiente para este período.</p>

  const prices = points.map((point) => point.price)
  const min = Math.min(...prices)
  const max = Math.max(...prices)
  const first = prices[0]!
  const last = prices.at(-1)!
  const up = last >= first
  const path = width > 0 ? pricePath(prices, width, HEIGHT) : ''
  const selected = active === null ? null : points[active]!
  const x = (i: number) => (i / (points.length - 1)) * width
  const y = (price: number) => 6 + (1 - (price - min) / (max - min || 1)) * (HEIGHT - 12)

  const track = (event: PointerEvent<SVGSVGElement>) => {
    const position = event.clientX - event.currentTarget.getBoundingClientRect().left
    setActive(Math.min(Math.max(Math.round((position / width) * (points.length - 1)), 0), points.length - 1))
  }

  return (
    <figure className="price-chart" data-up={up}>
      <div ref={box} className="price-chart-plot">
        {width > 0 && (
          <svg width={width} height={HEIGHT} role="img" onPointerMove={track} onPointerLeave={() => setActive(null)}
            aria-label={`${name}: de ${formatPrice(first)} em ${formatDay(points[0]!.day)} para ${formatPrice(last)} em ${formatDay(points.at(-1)!.day)}. Mínimo ${formatPrice(min)}, máximo ${formatPrice(max)}.`}>
            <path className="price-area" d={`${path}L${width} ${HEIGHT}L0 ${HEIGHT}Z`} />
            <path className="price-line" d={path} />
            {selected && active !== null && (
              <>
                <line className="price-cross" x1={x(active)} x2={x(active)} y1={0} y2={HEIGHT} />
                <circle className="price-dot" cx={x(active)} cy={y(selected.price)} r="5" />
              </>
            )}
          </svg>
        )}
      </div>
      <figcaption>
        {selected ? (
          <>
            <strong>{formatPrice(selected.price)}</strong> em {formatDay(selected.day)}
          </>
        ) : (
          <>
            Mínimo {formatPrice(min)}, máximo {formatPrice(max)}
          </>
        )}
      </figcaption>
    </figure>
  )
}
