import { useState, type CSSProperties } from 'react'

const CENTER = 100
const RADIUS = 62

/** Espinhos só no contorno de trás e de cima; o rosto fica limpo. */
const SPIKES = Array.from({ length: 13 }, (_, i) => {
  const angle = ((200 + i * 15) * Math.PI) / 180
  const point = (r: number, offset: number) =>
    `${(CENTER + r * Math.cos(angle + offset)).toFixed(1)} ${(CENTER + r * Math.sin(angle + offset)).toFixed(1)}`
  return `M${point(RADIUS - 8, -0.15)}L${point(RADIUS + 11, 0)}L${point(RADIUS - 8, 0.15)}Z`
})

/** Lino, o baiacu. Tocar nele faz inflar; level (0 a 1) diz o quanto ele já está inflado. */
export function Lino({ size = 160, level = 0.5 }: { size?: number; level?: number }) {
  const [touched, setTouched] = useState(false)
  const inflated = touched

  return (
    <button
      type="button"
      className="lino"
      data-puffed={inflated}
      style={{ width: size, height: size, '--puff': level } as CSSProperties}
      aria-label={inflated ? 'Lino, o baiacu, inflado' : 'Lino, o baiacu. Toque para inflar'}
      onClick={() => setTouched((value) => !value)}
    >
      <svg viewBox="0 0 220 200" aria-hidden="true">
        <defs>
          <radialGradient id="lino-body" cx="38%" cy="28%" r="80%">
            <stop offset="0" stopColor="#ffffff" />
            <stop offset="0.55" stopColor="#d9d3d4" />
            <stop offset="1" stopColor="#968d90" />
          </radialGradient>
          <clipPath id="lino-clip">
            <circle cx={CENTER} cy={CENTER} r={RADIUS} />
          </clipPath>
        </defs>
        <g className="lino-tail">
          <path d="M156 100c14-24 30-34 48-32 2 16-4 26-14 32 10 6 16 16 14 32-18 2-34-8-48-32z" fill="#f0531c" />
          <path d="M172 100h18M170 92l18-12M170 108l18 12" stroke="#b33608" strokeWidth="3" strokeLinecap="round" />
        </g>
        <g className="lino-body">
          <g className="lino-spikes" fill="#4a4448">
            {SPIKES.map((d) => (
              <path key={d} d={d} />
            ))}
          </g>
          <circle cx={CENTER} cy={CENTER} r={RADIUS} fill="url(#lino-body)" />
          <path
            d="M30 118c22 16 44 16 66 2s46-12 74 4v60H30z"
            fill="#ffffff"
            clipPath="url(#lino-clip)"
          />
          <g fill="#4a4448" opacity="0.35">
            <circle cx="112" cy="52" r="5" />
            <circle cx="132" cy="64" r="4" />
            <circle cx="128" cy="84" r="5.500" />
            <circle cx="148" cy="88" r="3.500" />
          </g>
          <path className="lino-fin" d="M112 112c16-10 30-8 38 4-10 10-24 12-38 4z" fill="#f0531c" />
          <g className="lino-eyes">
            <circle cx="62" cy="86" r="15" fill="#fff" />
            <circle cx="100" cy="86" r="15" fill="#fff" />
            <circle cx="60" cy="88" r="8.500" fill="#17161a" />
            <circle cx="98" cy="88" r="8.500" fill="#17161a" />
            <circle cx="63" cy="84" r="3" fill="#fff" />
            <circle cx="101" cy="84" r="3" fill="#fff" />
          </g>
          <circle cx="48" cy="108" r="7" fill="#ff6f5e" opacity="0.45" />
          <circle cx="114" cy="108" r="7" fill="#ff6f5e" opacity="0.45" />
          {inflated ? (
            <ellipse cx="80" cy="114" rx="6" ry="7" fill="#17161a" />
          ) : (
            <path d="M70 110c6 8 14 8 20 0" fill="none" stroke="#17161a" strokeWidth="4" strokeLinecap="round" />
          )}
        </g>
      </svg>
    </button>
  )
}
