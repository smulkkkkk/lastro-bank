import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import type { Notice } from '../lib/live'

const BELL = 'M6 16V11a6 6 0 0 1 12 0v5l2 2H4zM10 20a2 2 0 0 0 4 0'
const CHAT = 'M4 5h16v11H9l-5 4zM8 9.500h8M8 12.500h5'

const when = new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })

/** Sino com a contagem de não lidos. Abrir a lista marca tudo como lido. */
export function NoticeBell({
  items,
  unread,
  online,
  onOpen,
}: {
  items: Notice[]
  unread: number
  online: boolean
  onOpen: () => void
}) {
  const [open, setOpen] = useState(false)
  const box = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const close = (event: MouseEvent | KeyboardEvent) => {
      if (event instanceof KeyboardEvent ? event.key === 'Escape' : !box.current?.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', close)
    return () => {
      document.removeEventListener('mousedown', close)
      document.removeEventListener('keydown', close)
    }
  }, [open])

  return (
    <div className="notices" ref={box}>
      <button
        type="button"
        className="icon-button bell"
        aria-expanded={open}
        aria-label={unread > 0 ? `Avisos, ${unread} não lidos` : 'Avisos'}
        onClick={() => {
          setOpen((value) => !value)
          if (!open) onOpen()
        }}
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d={BELL} />
        </svg>
        {unread > 0 && <span className="badge">{unread > 9 ? '9+' : unread}</span>}
      </button>
      {open && (
        <div className="notice-panel" role="dialog" aria-label="Avisos">
          <div className="notice-head">
            <strong>Avisos</strong>
            <span className="live-dot" data-online={online}>
              {online ? 'ao vivo' : 'reconectando'}
            </span>
          </div>
          {items.length === 0 ? (
            <p className="muted notice-empty">Nada por aqui ainda. Quando um Pix chegar, você vê na hora.</p>
          ) : (
            <ul className="notice-list">
              {items.map((notice) => (
                <li key={notice.seq} data-kind={notice.kind} data-unread={!notice.readAt}>
                  <Link to={notice.link ?? '/app'} onClick={() => setOpen(false)}>
                    <strong>{notice.title}</strong>
                    <span>{notice.body}</span>
                    <time dateTime={notice.createdAt}>{when.format(new Date(notice.createdAt))}</time>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}

export function HelpLink() {
  return (
    <Link to="/app/ajuda" className="icon-button" aria-label="Ajuda com o Lino">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d={CHAT} />
      </svg>
    </Link>
  )
}

/** Aviso novo aparece no canto por alguns segundos; leitor de tela anuncia sem roubar o foco. */
export function Toasts({ items, onDismiss }: { items: Notice[]; onDismiss: (seq: number) => void }) {
  return (
    <div className="toasts" role="status" aria-live="polite">
      {items.map((notice) => (
        <div key={notice.seq} className="toast" data-kind={notice.kind}>
          <div>
            <strong>{notice.title}</strong>
            <span>{notice.body}</span>
          </div>
          <button type="button" className="icon-button" aria-label="Fechar aviso" onClick={() => onDismiss(notice.seq)}>
            ×
          </button>
        </div>
      ))}
    </div>
  )
}
