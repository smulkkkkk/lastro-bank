import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router'
import { AccountCard } from '../components/AccountCard'
import { BallastLine } from '../components/BallastLine'
import { DepositDialog } from '../components/DepositDialog'
import { Alert } from '../components/ui'
import { fetchStatement, type Entry } from '../lib/api'
import { formatCents } from '../lib/money'
import { messageOf } from '../lib/session'
import { useBank } from './AppShell'

export function Dashboard() {
  const { account, reload } = useBank()
  const [entries, setEntries] = useState<Entry[]>()
  const [error, setError] = useState('')
  const [depositing, setDepositing] = useState(false)
  const [notice, setNotice] = useState('')

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
        <p className="balance">{formatCents(account.availableCents)}</p>
        {account.heldCents > 0 && <p className="muted">{formatCents(account.heldCents)} reservados</p>}
        <button type="button" className="button large" onClick={() => setDepositing(true)}>
          Depositar
        </button>
        <p className="notice" role="status">
          {notice}
        </p>
      </section>

      <AccountCard branch={account.branch} number={account.number} />

      <section className="movements" aria-labelledby="movements-title">
        <div className="section-head">
          <h2 id="movements-title">Movimentações</h2>
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
          <BallastLine entries={entries} />
        ) : (
          <p className="state muted" role="status">
            Carregando movimentações…
          </p>
        )}
      </section>

      {depositing && (
        <DepositDialog
          accountId={account.id}
          onClose={() => setDepositing(false)}
          onDone={(cents) => {
            setDepositing(false)
            setNotice(`Depósito de ${formatCents(cents)} concluído.`)
            void reload()
            void loadEntries()
          }}
        />
      )}
    </div>
  )
}
