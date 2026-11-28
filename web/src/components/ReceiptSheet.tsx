import { useEffect, useRef, useState } from 'react'
import type { BillReceipt } from '../lib/api'
import { formatCents } from '../lib/money'
import { usePrivacy } from '../lib/privacy'

const full = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'long', timeStyle: 'short' })
const day = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'long', timeZone: 'UTC' })

/**
 * Comprovante de pagamento. Na tela, respeita o olho fechado; o texto compartilhado leva o valor
 * de verdade, porque quem compartilha quer mandar o comprovante inteiro.
 */
export function ReceiptSheet({ receipt, onClose }: { receipt: BillReceipt; onClose: () => void }) {
  const { money } = usePrivacy()
  const title = useRef<HTMLHeadingElement>(null)
  const [shared, setShared] = useState('')
  useEffect(() => title.current?.focus(), [receipt.id])

  const rows: [string, string][] = [
    ['Valor', money(receipt.amountCents)],
    ['Data', full.format(new Date(receipt.createdAt))],
    [receipt.kind === 'BOLETO' ? 'Para' : 'Operadora', receipt.recipient],
    ...(receipt.phone ? ([['Celular', receipt.phone]] as [string, string][]) : []),
    ...(receipt.dueDate ? ([['Vencimento', day.format(new Date(receipt.dueDate))]] as [string, string][]) : []),
    ['Pago por', receipt.payerName],
    ['Conta', receipt.payerAccount],
  ]

  const text = [
    receipt.kind === 'BOLETO' ? 'Comprovante de pagamento de boleto' : 'Comprovante de recarga',
    ...rows.map(([label, value]) => `${label}: ${label === 'Valor' ? formatCents(receipt.amountCents) : value}`),
    ...(receipt.line ? [`Linha digitável: ${receipt.line}`] : []),
    `Autenticação: ${receipt.authentication}`,
    'Lastro, banco de demonstração com dinheiro fictício',
  ].join('\n')

  async function share() {
    try {
      if (navigator.share) {
        await navigator.share({ title: 'Comprovante Lastro', text })
        return
      }
      await navigator.clipboard.writeText(text)
      setShared('Comprovante copiado. É só colar onde quiser.')
    } catch {
      // Fechar a janela de compartilhar não é erro.
    }
  }

  return (
    <article className="receipt-sheet" aria-labelledby="receipt-title">
      <header>
        <p className="receipt-kind">{receipt.kind === 'BOLETO' ? 'Boleto pago' : 'Recarga feita'}</p>
        <h2 id="receipt-title" ref={title} tabIndex={-1} className="balance">
          {money(receipt.amountCents)}
        </h2>
        <p className="muted">{receipt.description}</p>
      </header>
      <dl>
        {rows.map(([label, value]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
        {receipt.line && (
          <div className="receipt-wide">
            <dt>Linha digitável</dt>
            <dd>{receipt.line}</dd>
          </div>
        )}
        <div className="receipt-wide">
          <dt>Autenticação</dt>
          <dd className="receipt-auth">{receipt.authentication}</dd>
        </div>
      </dl>
      <p className="receipt-note">Banco de demonstração. O dinheiro é fictício.</p>
      <div className="receipt-actions">
        <button type="button" className="button" onClick={() => void share()}>
          Compartilhar
        </button>
        <button type="button" className="button quiet" onClick={() => window.print()}>
          Salvar PDF
        </button>
        <button type="button" className="link-button" onClick={onClose}>
          Fechar comprovante
        </button>
      </div>
      <p className="notice" role="status">
        {shared}
      </p>
    </article>
  )
}
