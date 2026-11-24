import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import * as api from './api'

type Status = 'loading' | 'in' | 'out' | 'offline'

interface Session {
  status: Status
  login: (email: string, password: string) => Promise<void>
  logout: () => Promise<void>
  retry: () => void
}

const SessionContext = createContext<Session | null>(null)

export function SessionProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<Status>('loading')

  const resume = () => {
    setStatus('loading')
    api.resumeSession().then(
      (active) => setStatus(active ? 'in' : 'out'),
      () => setStatus('offline'),
    )
  }

  useEffect(() => {
    api.setSessionEndHandler(() => setStatus('out'))
    resume()
  }, [])

  const session = useMemo<Session>(
    () => ({
      status,
      login: async (email, password) => {
        await api.login(email, password)
        setStatus('in')
      },
      logout: async () => {
        await api.logout()
        setStatus('out')
      },
      retry: resume,
    }),
    [status],
  )

  return <SessionContext value={session}>{children}</SessionContext>
}

export function useSession() {
  const session = useContext(SessionContext)
  if (!session) throw new Error('useSession fora do SessionProvider')
  return session
}

export function messageOf(error: unknown): string {
  return error instanceof api.ApiError ? error.message : 'Algo deu errado. Tente de novo.'
}
