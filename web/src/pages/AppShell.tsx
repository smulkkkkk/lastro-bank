import { useCallback, useEffect, useState } from 'react'
import { Navigate, NavLink, Outlet, useOutletContext } from 'react-router'
import { Alert, Brand, ThemeToggle } from '../components/ui'
import { Lino } from '../components/Lino'
import { HelpLink, NoticeBell, Toasts } from '../components/Notices'
import { useNotices, type Notice } from '../lib/live'
import { currentRole, fetchAccounts, fetchCards, updateCard, type Account, type Card, type CardChange } from '../lib/api'
import { PrivacyToggle } from '../lib/privacy'
import { messageOf, useSession } from '../lib/session'

interface BankContext {
  account: Account
  cards: Card[]
  reload: () => Promise<void>
  changeCard: (id: string, change: CardChange) => Promise<void>
}

export const useBank = () => useOutletContext<BankContext>()

const ICONS = {
  home: 'M4 11l8-7 8 7v9h-5v-6H9v6H4z',
  pix: 'M4 9h14l-4-4M20 15H6l4 4',
  vaults: 'M4 5v13a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V5M4 11c3-2 5-2 8 0s5 2 8 0',
  credit: 'M12 3v18M17 7H9.500a3 3 0 0 0 0 6h5a3 3 0 0 1 0 6H6',
  invest: 'M4 19l5-6 4 3 7-9M15 7h5v5',
  cards: 'M3 8a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM3 10.500h18',
  statement: 'M6 3h12v18l-3-2-3 2-3-2-3 2zM9.500 8h5M9.500 12h5',
  review: 'M12 3l8 3v6c0 4.500-3.500 8-8 9-4.500-1-8-4.500-8-9V6zM9 12l2 2 4-4',
  profile: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4.500 21a7.500 7.500 0 0 1 15 0',
}

const PLACES = [
  { to: '/app', label: 'Início', icon: ICONS.home },
  { to: '/app/pix', label: 'Pix', icon: ICONS.pix },
  { to: '/app/cartoes', label: 'Cartões', icon: ICONS.cards },
  { to: '/app/caixinhas', label: 'Caixinhas', icon: ICONS.vaults },
  { to: '/app/investir', label: 'Investir', icon: ICONS.invest },
  { to: '/app/credito', label: 'Crédito', icon: ICONS.credit },
  { to: '/app/extrato', label: 'Extrato', icon: ICONS.statement },
  { to: '/app/conta', label: 'Conta', icon: ICONS.profile },
]

/** Avisos ao vivo. Só liga com a conta carregada, quando o token já existe. */
function LiveTools({ reload }: { reload: () => Promise<void> }) {
  const [toasts, setToasts] = useState<Notice[]>([])
  const dismiss = useCallback((seq: number) => setToasts((current) => current.filter((n) => n.seq !== seq)), [])
  const notices = useNotices((notice) => {
    setToasts((current) => [...current, notice].slice(-3))
    setTimeout(() => dismiss(notice.seq), 6000)
    // Pix mexe no saldo: atualiza a tela sem o cliente precisar recarregar.
    if (notice.kind.startsWith('PIX_')) void reload()
  })
  return (
    <>
      <HelpLink />
      <NoticeBell items={notices.items} unread={notices.unread} online={notices.online} onOpen={notices.markAllRead} />
      <Toasts items={toasts} onDismiss={dismiss} />
    </>
  )
}

export function AppShell() {
  const session = useSession()
  const [account, setAccount] = useState<Account>()
  const [cards, setCards] = useState<Card[]>()
  const [error, setError] = useState('')

  const reload = useCallback(async () => {
    setError('')
    try {
      const [accounts, owned] = await Promise.all([fetchAccounts(), fetchCards()])
      setAccount(accounts.find((item) => item.kind === 'CHECKING'))
      setCards(owned)
    } catch (failure) {
      setError(messageOf(failure))
    }
  }, [])

  const changeCard = useCallback(async (id: string, change: CardChange) => {
    const updated = await updateCard(id, change)
    setCards((current) =>
      current?.map((card) =>
        card.id === id ? updated : updated.primary ? { ...card, primary: false } : card,
      ),
    )
  }, [])

  useEffect(() => {
    if (session.status !== 'in') return
    void reload()
    // Um Pix pode ter chegado enquanto a aba estava escondida.
    const refresh = () => {
      if (!document.hidden) void reload()
    }
    document.addEventListener('visibilitychange', refresh)
    return () => document.removeEventListener('visibilitychange', refresh)
  }, [session.status, reload])

  if (session.status === 'out') return <Navigate to="/entrar" replace />

  const offline = session.status === 'offline'

  return (
    <div className="app">
      <header className="app-top">
        <Brand to="/app" />
        <div className="app-tools">
          {account && <LiveTools reload={reload} />}
          <PrivacyToggle />
          <ThemeToggle />
        </div>
      </header>
      <nav className="app-nav" aria-label="Principal">
        <Brand to="/app" />
        {[...PLACES, ...(currentRole() === 'ANALYST' ? [{ to: '/app/analise', label: 'Análise', icon: ICONS.review }] : [])].map((place) => (
          <NavLink key={place.to} to={place.to} end={place.to === '/app'} data-place={place.to.split('/').pop()}>
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d={place.icon} />
            </svg>
            {place.label}
          </NavLink>
        ))}
      </nav>
      <main className="app-main">
        {error || offline ? (
          <div className="state">
            <Lino size={120} />
            <Alert>{error || 'Sem conexão com o banco. Confira se a API está rodando.'}</Alert>
            <button type="button" className="button" onClick={() => (offline ? session.retry() : void reload())}>
              Tentar de novo
            </button>
          </div>
        ) : account && cards ? (
          <Outlet context={{ account, cards, reload, changeCard } satisfies BankContext} />
        ) : (
          <p className="state muted" role="status">
            Carregando sua conta…
          </p>
        )}
      </main>
    </div>
  )
}
