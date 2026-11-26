import { useCallback, useEffect, useState } from 'react'
import { formatDate } from '../components/EntryRow'
import { Alert } from '../components/ui'
import { decideReview, fetchReviews, type ReviewItem } from '../lib/api'
import { formatCents } from '../lib/money'
import { messageOf } from '../lib/session'

/** Fila do analista. Os motivos vêm do sentinel; o cliente nunca vê esta tela. */
export function Reviews() {
  const [items, setItems] = useState<ReviewItem[]>()
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  const load = useCallback(async () => {
    setError('')
    try {
      setItems(await fetchReviews())
    } catch (failure) {
      setError(messageOf(failure))
    }
  }, [])

  useEffect(() => {
    void load()
    const timer = setInterval(() => {
      if (!document.hidden) void load()
    }, 15_000)
    return () => clearInterval(timer)
  }, [load])

  return (
    <div className="reviews">
      <div className="section-head">
        <h1>Análise de Pix</h1>
        <p className="muted">Transferências que o antifraude mandou para revisão humana</p>
      </div>
      <p className="notice" role="status">
        {notice}
      </p>
      {error && <Alert>{error}</Alert>}
      {items?.length === 0 && <p className="muted">Nenhum Pix esperando revisão.</p>}
      <ul className="review-list">
        {items?.map((item) => (
          <ReviewCard key={item.id} item={item} onDone={(message) => {
            setNotice(message)
            void load()
          }} />
        ))}
      </ul>
    </div>
  )
}

function ReviewCard({ item, onDone }: { item: ReviewItem; onDone: (message: string) => void }) {
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const [sending, setSending] = useState(false)

  const decide = async (decision: 'APPROVE' | 'DENY') => {
    if (reason.trim().length < 5) return setError('Escreva o motivo da decisão, com pelo menos 5 letras.')
    setSending(true)
    setError('')
    try {
      await decideReview(item.id, decision, reason.trim())
      onDone(decision === 'APPROVE' ? `Pix de ${formatCents(item.amountCents)} aprovado.` : `Pix de ${formatCents(item.amountCents)} recusado e devolvido.`)
    } catch (failure) {
      setError(messageOf(failure))
      setSending(false)
    }
  }

  return (
    <li className="review">
      <div className="review-head">
        <div>
          <p className="review-amount">{formatCents(item.amountCents)}</p>
          <p className="muted">
            {item.senderName} para {item.recipientName}, {formatDate(item.createdAt)}
          </p>
        </div>
        {item.score !== null && (
          <span className="score" data-level={item.score >= 70 ? 'high' : 'mid'}>
            Nota {item.score}
          </span>
        )}
      </div>
      <ul className="reasons">
        {item.reasons.map((reason) => (
          <li key={reason.code}>
            <strong>+{reason.points}</strong> {reason.text}
          </li>
        ))}
      </ul>
      <div className="field">
        <label htmlFor={`motivo-${item.id}`}>Motivo da decisão</label>
        <input id={`motivo-${item.id}`} maxLength={280} value={reason} onChange={(event) => setReason(event.target.value)} />
      </div>
      {error && <Alert>{error}</Alert>}
      <div className="actions">
        <button type="button" className="button" disabled={sending} onClick={() => void decide('APPROVE')}>
          Aprovar
        </button>
        <button type="button" className="button quiet" disabled={sending} onClick={() => void decide('DENY')}>
          Recusar e devolver
        </button>
      </div>
    </li>
  )
}
