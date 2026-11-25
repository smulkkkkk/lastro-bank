import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'
import { formatCents } from './money'

const KEY = 'lastro-ocultar'

interface Privacy {
  hidden: boolean
  toggle: () => void
  money: (cents: number) => string
}

const PrivacyContext = createContext<Privacy | null>(null)

function stored() {
  try {
    return localStorage.getItem(KEY) === '1'
  } catch {
    return false
  }
}

/** Esconder valores vale para o app todo e continua valendo ao reabrir. */
export function PrivacyProvider({ children }: { children: ReactNode }) {
  const [hidden, setHidden] = useState(stored)
  const privacy = useMemo<Privacy>(
    () => ({
      hidden,
      toggle: () => {
        try {
          localStorage.setItem(KEY, hidden ? '0' : '1')
        } catch {
          // Sem armazenamento, vale até fechar a página.
        }
        setHidden(!hidden)
      },
      money: (cents) => (hidden ? 'R$ ••••' : formatCents(cents)),
    }),
    [hidden],
  )
  return <PrivacyContext value={privacy}>{children}</PrivacyContext>
}

export function usePrivacy() {
  const privacy = useContext(PrivacyContext)
  if (!privacy) throw new Error('usePrivacy fora do PrivacyProvider')
  return privacy
}

export function PrivacyToggle() {
  const { hidden, toggle } = usePrivacy()
  return (
    <button
      type="button"
      className="icon-button"
      aria-pressed={hidden}
      aria-label={hidden ? 'Mostrar valores' : 'Esconder valores'}
      onClick={toggle}
    >
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M2 12s3.500-6.500 10-6.500S22 12 22 12s-3.500 6.500-10 6.500S2 12 2 12z" />
        <circle cx="12" cy="12" r="3" />
        {hidden && <path d="M4 20L20 4" />}
      </svg>
    </button>
  )
}
