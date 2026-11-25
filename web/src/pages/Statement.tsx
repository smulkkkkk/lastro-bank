import { useEffect, useRef, useState } from 'react'
import { EntryRow } from '../components/EntryRow'
import { TraceDialog } from '../components/TraceDialog'
import { Alert } from '../components/ui'
import { fetchStatement, type Entry } from '../lib/api'
import { messageOf } from '../lib/session'
import { useBank } from './AppShell'

const DAY = 24 * 60 * 60 * 1000
const PERIODS = [
  { value: '', label: 'Todo o período' },
  { value: '7', label: 'Últimos 7 dias' },
  { value: '30', label: 'Últimos 30 dias' },
]
const KINDS = [
  { value: '', label: 'Todos os tipos' },
  { value: 'TRANSFER', label: 'Pix' },
  { value: 'VAULT_IN', label: 'Guardado em caixinha' },
  { value: 'VAULT_OUT', label: 'Resgate de caixinha' },
  { value: 'DEPOSIT', label: 'Depósitos' },
  { value: 'WELCOME', label: 'Boas-vindas' },
]

export function Statement() {
  const { account } = useBank()
  const [kind, setKind] = useState('')
  const [days, setDays] = useState('')
  const [tracing, setTracing] = useState<Entry>()
  const [entries, setEntries] = useState<Entry[]>([])
  const [cursor, setCursor] = useState<number | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  // Resposta de um filtro antigo não pode sobrescrever a do filtro atual.
  const request = useRef(0)

  const load = async (before?: number) => {
    const current = ++request.current
    setLoading(true)
    setError('')
    if (before === undefined) {
      setEntries([])
      setCursor(null)
    }
    try {
      const page = await fetchStatement(account.id, {
        kind,
        before,
        from: days ? new Date(Date.now() - Number(days) * DAY).toISOString() : undefined,
      })
      if (current !== request.current) return
      setEntries((previous) => (before === undefined ? page.items : [...previous, ...page.items]))
      setCursor(page.nextCursor)
    } catch (failure) {
      if (current === request.current) setError(messageOf(failure))
    } finally {
      if (current === request.current) setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [account.id, kind, days])

  return (
    <div className="statement">
      <div className="section-head">
        <h1>Extrato</h1>
        <div className="filters">
          <select aria-label="Período" value={days} onChange={(event) => setDays(event.target.value)}>
            {PERIODS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <select aria-label="Tipo" value={kind} onChange={(event) => setKind(event.target.value)}>
            {KINDS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
      </div>

      {entries.length > 0 && (
        <ol className="entries">
          {entries.map((entry) => (
            <EntryRow key={entry.entryId} entry={entry} onTrace={setTracing} />
          ))}
        </ol>
      )}
      {tracing && <TraceDialog accountId={account.id} entry={tracing} onClose={() => setTracing(undefined)} />}
      {loading && (
        <p className="state muted" role="status">
          Carregando lançamentos…
        </p>
      )}
      {error && (
        <div className="state">
          <Alert>{error}</Alert>
          <button type="button" className="button" onClick={() => void load(cursor ?? undefined)}>
            Tentar de novo
          </button>
        </div>
      )}
      {!loading && !error && entries.length === 0 && (
        <p className="state muted">Nenhum lançamento com estes filtros. Escolha outro período ou tipo.</p>
      )}
      {!loading && !error && cursor !== null && (
        <button type="button" className="button quiet" onClick={() => void load(cursor)}>
          Carregar mais
        </button>
      )}
    </div>
  )
}
