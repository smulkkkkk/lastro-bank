import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router'
import { BallastLine } from '../components/BallastLine'
import { BankCard } from '../components/BankCard'
import { DepositDialog } from '../components/DepositDialog'
import { Tiles } from '../components/Tiles'
import { TraceDialog } from '../components/TraceDialog'
import { Alert } from '../components/ui'
import { fetchStatement, type Entry } from '../lib/api'
import { usePrivacy } from '../lib/privacy'
import { messageOf } from '../lib/session'
import { useBank } from './AppShell'

export function Dashboard() {
  const { account, cards, reload } = useBank()
  const { money } = usePrivacy()
  const [entries, setEntries] = useState<Entry[]>()
  const [error, setError] = useState('')
  const [tracing, setTracing] = useState<Entry>()
  const [depositing, setDepositing] = useState(false)
  const [deposited, setDeposited] = useState<number | null>(null)

  const primary = cards.find((card) => card.primary) ?? cards[0]

  const loadEntries = useCallback(async () => {
    setError('')
    try {
      setEntries((await fetchStatement(account.id, { limit: 12 })).items)
    } catch (failure) {
      setError(messageOf(failure))
    }
  }, [account.id])

  useEffect(() => {
    void loadEntries()
  }, [loadEntries])

  return (
    <div className="dashboard">
      <section className="summary" aria-label="Saldo">
        <h1 className="balance-label">Saldo disponível</h1>
        <p className="balance">{money(account.availableCents)}</p>
        {account.heldCents > 0 && <p className="muted">{money(account.heldCents)} reservados</p>}
        <div className="actions">
          <Link className="button large" to="/app/pix">
            Fazer Pix
          </Link>
          <button type="button" className="button quiet large" onClick={() => setDepositing(true)}>
            Depositar
          </button>
        </div>
        <p className="notice" role="status">
          {deposited !== null && `Depósito de ${money(deposited)} concluído.`}
        </p>
      </section>

      {primary && (
        <Link to="/app/cartoes" className="floating-card" aria-label={`Cartão principal, final ${primary.last4}. Abrir carteira`}>
          <BankCard card={primary} interactive />
        </Link>
      )}

      <Tiles />

      <section className="movements" aria-labelledby="movements-title">
        <div className="section-head">
          <div>
            <h2 id="movements-title">Movimentações</h2>
            <p className="muted">Toque em uma para ver o rastro do dinheiro.</p>
          </div>
          <Link to="/app/extrato">Ver extrato completo</Link>
        </div>
        {error ? (
          <div className="state">
            <Alert>{error}</Alert>
            <button type="button" className="button" onClick={() => void loadEntries()}>
              Tentar de novo
            </button>
          </div>
        ) : entries ? (
          <BallastLine entries={entries} onTrace={setTracing} />
        ) : (
          <p className="state muted" role="status">
            Carregando movimentações…
          </p>
        )}
      </section>

      {tracing && <TraceDialog accountId={account.id} entry={tracing} onClose={() => setTracing(undefined)} />}

      {depositing && (
        <DepositDialog
          accountId={account.id}
          onClose={() => setDepositing(false)}
          onDone={(cents) => {
            setDepositing(false)
            setDeposited(cents)
            void reload()
            void loadEntries()
          }}
        />
      )}
    </div>
  )
}
