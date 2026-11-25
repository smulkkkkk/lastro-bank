import type { Entry } from '../lib/api'
import { usePrivacy } from '../lib/privacy'

const dateTime = new Intl.DateTimeFormat('pt-BR', {
  day: '2-digit',
  month: 'short',
  hour: '2-digit',
  minute: '2-digit',
  timeZone: 'America/Sao_Paulo',
})

export const formatDate = (iso: string) => dateTime.format(new Date(iso))

interface Props {
  entry: Entry
  active?: boolean
  onTrace: (entry: Entry) => void
}

export function EntryRow({ entry, active = false, onTrace }: Props) {
  const { money } = usePrivacy()
  const credit = entry.direction === 'CREDIT'
  return (
    <li className="entry" data-active={active}>
      <button type="button" className="entry-button" onClick={() => onTrace(entry)} aria-haspopup="dialog">
        <span className="entry-text">
          <span className="entry-title">{entry.description}</span>
          <time dateTime={entry.createdAt}>{formatDate(entry.createdAt)}</time>
        </span>
        <span className="entry-values">
          <span className={credit ? 'amount credit' : 'amount'}>
            {credit ? '+' : '−'} {money(entry.amountCents)}
          </span>
          <span className="entry-balance">Saldo {money(entry.balanceAfterCents)}</span>
        </span>
      </button>
    </li>
  )
}
