import type { Entry } from '../lib/api'
import { formatCents } from '../lib/money'

const dateTime = new Intl.DateTimeFormat('pt-BR', {
  day: '2-digit',
  month: 'short',
  hour: '2-digit',
  minute: '2-digit',
  timeZone: 'America/Sao_Paulo',
})

export const formatDate = (iso: string) => dateTime.format(new Date(iso))

export function EntryRow({ entry, active = false }: { entry: Entry; active?: boolean }) {
  const credit = entry.direction === 'CREDIT'
  return (
    <li className="entry" data-active={active}>
      <div>
        <span className="entry-title">{entry.description}</span>
        <time dateTime={entry.createdAt}>{formatDate(entry.createdAt)}</time>
      </div>
      <div className="entry-values">
        <span className={credit ? 'amount credit' : 'amount'}>
          {credit ? '+' : '−'} {formatCents(entry.amountCents)}
        </span>
        <span className="entry-balance">Saldo {formatCents(entry.balanceAfterCents)}</span>
      </div>
    </li>
  )
}
