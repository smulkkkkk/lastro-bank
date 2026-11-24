import { useCallback, useEffect, useState } from 'react'
import { Navigate, NavLink, Outlet, useOutletContext } from 'react-router'
import { Alert, Brand, ThemeToggle } from '../components/ui'
import { fetchAccounts, type Account } from '../lib/api'
import { messageOf, useSession } from '../lib/session'

interface BankContext {
  account: Account
  reload: () => Promise<void>
}

export const useBank = () => useOutletContext<BankContext>()

export function AppShell() {
  const session = useSession()
  const [account, setAccount] = useState<Account>()
  const [error, setError] = useState('')
  const [logoutError, setLogoutError] = useState('')

  const reload = useCallback(async () => {
    setError('')
    try {
      const accounts = await fetchAccounts()
      setAccount(accounts.find((item) => item.kind === 'CHECKING'))
    } catch (failure) {
      setError(messageOf(failure))
    }
  }, [])

  useEffect(() => {
    if (session.status === 'in') void reload()
  }, [session.status, reload])

  if (session.status === 'out') return <Navigate to="/entrar" replace />

  const offline = session.status === 'offline'

  return (
    <div className="app">
      <header className="topbar app-bar">
        <Brand />
        <nav aria-label="Conta" className="app-nav">
          <NavLink to="/app" end>
            Painel
          </NavLink>
          <NavLink to="/app/extrato">Extrato</NavLink>
        </nav>
        <div className="app-tools">
          <ThemeToggle />
          <button
            type="button"
            className="button quiet"
            onClick={() => session.logout().catch((failure) => setLogoutError(messageOf(failure)))}
          >
            Sair
          </button>
        </div>
      </header>
      <main className="app-main">
        {logoutError && <Alert>{logoutError}</Alert>}
        {error || offline ? (
          <div className="state">
            <Alert>{error || 'Sem conexão com o banco. Confira se a API está rodando.'}</Alert>
            <button type="button" className="button" onClick={() => (offline ? session.retry() : void reload())}>
              Tentar de novo
            </button>
          </div>
        ) : account ? (
          <Outlet context={{ account, reload } satisfies BankContext} />
        ) : (
          <p className="state muted" role="status">
            Carregando sua conta…
          </p>
        )}
      </main>
    </div>
  )
}
