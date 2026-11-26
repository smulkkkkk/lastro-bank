import { useState } from 'react'
import { Lino } from '../components/Lino'
import { Alert, Switch } from '../components/ui'
import { usePrivacy } from '../lib/privacy'
import { messageOf, useSession } from '../lib/session'
import { useBank } from './AppShell'

const NEXT = [
  { title: 'Boleto, recarga e compras', items: 'Contas fictícias e histórico por cartão.' },
  { title: 'Avisos e suporte', items: 'Notificações ao vivo, chat com o Lino e atendimento no WhatsApp.' },
  { title: 'Bolhas e parceiros', items: 'Moeda do app, descontos, outros bancos e aproximação simulada.' },
]

export function Profile() {
  const { account } = useBank()
  const session = useSession()
  const { hidden, toggle } = usePrivacy()
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(`Agência ${account.branch}, conta ${account.number}`)
      setNotice('Dados copiados.')
    } catch {
      setNotice('Não deu para copiar. Selecione os números e copie.')
    }
  }

  return (
    <div className="profile">
      <h1>Conta</h1>
      <section className="account-data" aria-label="Dados da conta">
        <dl>
          <div>
            <dt>Agência</dt>
            <dd>{account.branch}</dd>
          </div>
          <div>
            <dt>Conta</dt>
            <dd>{account.number}</dd>
          </div>
        </dl>
        <button type="button" className="button quiet" onClick={() => void copy()}>
          Copiar dados
        </button>
        <p className="notice" role="status">
          {notice}
        </p>
      </section>

      <section>
        <Switch
          label="Esconder valores"
          hint="Saldos e lançamentos ficam ocultos em todas as telas."
          checked={hidden}
          onChange={toggle}
        />
        {error && <Alert>{error}</Alert>}
        <button
          type="button"
          className="button quiet"
          onClick={() => session.logout().catch((failure) => setError(messageOf(failure)))}
        >
          Sair da conta
        </button>
      </section>

      <section className="next">
        <div className="next-head">
          <h2>O que o Lino está preparando</h2>
          <Lino size={110} />
        </div>
        <ol>
          {NEXT.map((step) => (
            <li key={step.title}>
              <strong>{step.title}</strong>
              <span className="muted">{step.items}</span>
            </li>
          ))}
        </ol>
      </section>
    </div>
  )
}
