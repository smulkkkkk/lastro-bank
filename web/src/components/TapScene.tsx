export type TapDevice = 'CARD' | 'PHONE' | 'WATCH'

const NAMES: Record<TapDevice, string> = { CARD: 'cartão', PHONE: 'celular', WATCH: 'relógio' }

/** O aparelho chega na maquininha, as ondas saem e a tela confirma. Decorativa: o texto ao lado diz o que houve. */
export function TapScene({ device }: { device: TapDevice }) {
  return (
    <svg className="tap-scene" data-device={device} viewBox="0 0 260 150" role="img" aria-label={`Pagamento aproximado pelo ${NAMES[device]}`}>
      <g className="pos">
        <rect x="168" y="18" width="70" height="118" rx="12" />
        <rect className="pos-screen" x="178" y="30" width="50" height="40" rx="4" />
        <path className="pos-check" d="M192 50l7 7 14-15" />
        {[0, 1, 2].map((row) =>
          [0, 1, 2].map((col) => <rect key={`${row}${col}`} className="pos-key" x={182 + col * 15} y={82 + row * 14} width="11" height="9" rx="2" />),
        )}
      </g>
      <g className="waves">
        <path d="M150 62c6 6 6 20 0 26" />
        <path d="M142 54c10 10 10 32 0 42" />
        <path d="M134 46c14 14 14 44 0 58" />
      </g>
      <g className="device">
        {device === 'CARD' && (
          <>
            <rect x="40" y="48" width="84" height="54" rx="7" className="device-body" />
            <rect x="50" y="62" width="16" height="12" rx="2" className="device-chip" />
            <path d="M100 66c3 3 3 9 0 12M106 62c5 5 5 15 0 20" className="device-line" />
          </>
        )}
        {device === 'PHONE' && (
          <>
            <rect x="62" y="22" width="52" height="106" rx="10" className="device-body" />
            <rect x="68" y="32" width="40" height="84" rx="4" className="device-screen" />
            <circle cx="88" cy="74" r="10" className="device-line" />
          </>
        )}
        {device === 'WATCH' && (
          <>
            <rect x="76" y="20" width="28" height="30" rx="6" className="device-strap" />
            <rect x="76" y="100" width="28" height="30" rx="6" className="device-strap" />
            <rect x="66" y="46" width="48" height="58" rx="14" className="device-body" />
            <rect x="72" y="52" width="36" height="46" rx="10" className="device-screen" />
          </>
        )}
      </g>
    </svg>
  )
}
