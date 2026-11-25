import { useEffect, useRef, useState } from 'react'
import { fetchTrace, type Entry, type Trace } from '../lib/api'
import { usePrivacy } from '../lib/privacy'
import { ribbons } from '../lib/ribbons'
import { messageOf } from '../lib/session'
import { formatDate } from './EntryRow'
import { Alert } from './ui'

const WIDTH = 220
const ROW = 86

interface Props {
  accountId: string
  entry: Entry
  onClose: () => void
}

/** Rastro: segue o dinheiro de um lançamento até a origem, ou até o destino. */
export function TraceDialog({ accountId, entry, onClose }: Props) {
  const dialog = useRef<HTMLDialogElement>(null)
  const { money, hidden } = usePrivacy()
  const [trace, setTrace] = useState<Trace>()
  const [error, setError] = useState('')
  const spent = entry.direction === 'DEBIT'

  useEffect(() => {
    dialog.current?.showModal()
    let current = true
    fetchTrace(accountId, entry.entryId).then(
      (result) => current && setTrace(result),
      (failure) => current && setError(messageOf(failure)),
    )
    return () => {
      current = false
    }
  }, [accountId, entry.entryId])

  const rows = (trace?.items ?? []).map((item) => ({
    id: String(item.entryId),
    title: item.description,
    detail: formatDate(item.createdAt),
    cents: item.partCents,
    still: false,
  }))
  if (trace && trace.remainingCents > 0) {
    rows.push({ id: 'saldo', title: 'Ainda na conta', detail: 'Não foi gasto', cents: trace.remainingCents, still: true })
  }
  const height = Math.max(rows.length * ROW, ROW)
  const shapes = ribbons(rows.map((row) => row.cents), WIDTH, height)

  return (
    <dialog ref={dialog} className="dialog trace" aria-labelledby="trace-title" onClose={onClose}>
      <h2 id="trace-title">{spent ? 'De onde veio este dinheiro' : 'Para onde foi este dinheiro'}</h2>
      <p className="trace-entry">
        <span>{entry.description}</span>
        <strong>{money(entry.amountCents)}</strong>
      </p>

      {error && <Alert>{error}</Alert>}
      {!trace && !error && (
        <p className="muted" role="status">
          Seguindo o dinheiro…
        </p>
      )}
      {trace && (
        <div className="trace-flow">
          <ol style={{ height }}>
            {rows.map((row, i) => (
              <li key={row.id} style={{ top: shapes[i]?.y }}>
                <span className="trace-name">{row.title}</span>
                <strong>
                  {money(row.cents)}
                  {/* A proporção também revelaria valores. */}
                  {!hidden && rows.length > 1 && (
                    <span className="muted"> {Math.round((row.cents / entry.amountCents) * 100)}%</span>
                  )}
                </strong>
                <span className="muted">{row.detail}</span>
              </li>
            ))}
          </ol>
          <svg viewBox={`0 0 ${WIDTH} ${height}`} style={{ height }} preserveAspectRatio="none" aria-hidden="true">
            {shapes.map((shape, i) => (
              <path key={rows[i]!.id} d={shape.path} data-still={rows[i]!.still} style={{ animationDelay: `${i * 90}ms` }} />
            ))}
          </svg>
        </div>
      )}
      <p className="muted trace-note">
        {spent
          ? 'O primeiro dinheiro a entrar é o primeiro a sair.'
          : 'O dinheiro mais antigo da conta é sempre gasto antes deste.'}
      </p>
      <div className="dialog-actions">
        <button type="button" className="button" onClick={() => dialog.current?.close()}>
          Fechar
        </button>
      </div>
    </dialog>
  )
}
