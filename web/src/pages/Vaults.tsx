import { useCallback, useEffect, useId, useRef, useState, type CSSProperties, type FormEvent } from 'react'
import { Lino } from '../components/Lino'
import { Alert, Field } from '../components/ui'
import {
  createVault,
  deleteVault,
  fetchVaults,
  moveVault,
  type Cdi,
  type Vault,
  type VaultOverview,
} from '../lib/api'
import { centsFromInput, formatCents } from '../lib/money'
import { usePrivacy } from '../lib/privacy'
import { project, puff, waterLevel } from '../lib/savings'
import { messageOf } from '../lib/session'
import { useBank } from './AppShell'

const percent = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
// Datas vêm como AAAA-MM-DD, sem hora: ler em UTC evita mostrar o dia anterior.
const day = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'medium', timeZone: 'UTC' })
const formatDay = (iso: string) => day.format(new Date(`${iso}T00:00:00Z`))

type Open = { type: 'new' } | { type: 'move'; vault: Vault; way: 'deposits' | 'withdrawals' } | null

export function Vaults() {
  const { account, reload } = useBank()
  const { money } = usePrivacy()
  const [overview, setOverview] = useState<VaultOverview>()
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [open, setOpen] = useState<Open>(null)
  const [removing, setRemoving] = useState<string>()

  const load = useCallback(async () => {
    setError('')
    try {
      setOverview(await fetchVaults())
    } catch (failure) {
      setError(messageOf(failure))
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const done = (message: string) => {
    setOpen(null)
    setNotice(message)
    void load()
    void reload()
  }

  const remove = async (vault: Vault) => {
    setRemoving(undefined)
    try {
      await deleteVault(vault.id)
      done(`Caixinha ${vault.name} excluída.`)
    } catch (failure) {
      setNotice(messageOf(failure))
    }
  }

  if (!overview) {
    return error ? (
      <div className="state">
        <Alert>{error}</Alert>
        <button type="button" className="button" onClick={() => void load()}>
          Tentar de novo
        </button>
      </div>
    ) : (
      <p className="state muted" role="status">
        Carregando caixinhas…
      </p>
    )
  }

  return (
    <div className="vaults">
      <section className="vaults-top" aria-label="Total guardado">
        <div className="summary">
          <h1 className="balance-label">Guardado nas caixinhas</h1>
          <p className="balance">{money(overview.totalCents)}</p>
          <CdiLine cdi={overview.cdi} />
          <button type="button" className="button large" onClick={() => setOpen({ type: 'new' })}>
            Criar caixinha
          </button>
          <p className="notice" role="status">
            {notice}
          </p>
        </div>
        <Lino size={190} level={puff(overview.totalCents)} />
      </section>

      {error && <Alert>{error}</Alert>}
      {overview.items.length === 0 ? (
        <p className="muted vaults-empty">
          O Lino está murcho. Crie uma caixinha e guarde qualquer valor para ele começar a inflar.
        </p>
      ) : (
        <ul className="tanks">
          {overview.items.map((vault) => (
            <li key={vault.id} className="tank">
              <Tank vault={vault} total={overview.totalCents} />
              <div className="tank-actions">
                {removing === vault.id ? (
                  <>
                    <button type="button" className="button" onClick={() => void remove(vault)}>
                      Excluir
                    </button>
                    <button type="button" className="button quiet" onClick={() => setRemoving(undefined)}>
                      Manter
                    </button>
                  </>
                ) : (
                  <>
                    <button type="button" className="button" onClick={() => setOpen({ type: 'move', vault, way: 'deposits' })}>
                      Guardar
                    </button>
                    {vault.balanceCents > 0 && !vault.locked && (
                      <button type="button" className="button quiet" onClick={() => setOpen({ type: 'move', vault, way: 'withdrawals' })}>
                        Resgatar
                      </button>
                    )}
                    {vault.balanceCents === 0 && (
                      <button type="button" className="button quiet" onClick={() => setRemoving(vault.id)}>
                        Excluir
                      </button>
                    )}
                  </>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {overview.cdi && <Simulator cdi={overview.cdi} start={overview.totalCents} />}

      {open?.type === 'new' && <NewVault onClose={() => setOpen(null)} onDone={(name) => done(`Caixinha ${name} criada.`)} />}
      {open?.type === 'move' && (
        <MoveDialog
          vault={open.vault}
          way={open.way}
          available={open.way === 'deposits' ? account.availableCents : open.vault.balanceCents}
          onClose={() => setOpen(null)}
          onDone={() => done(open.way === 'deposits' ? 'Dinheiro guardado.' : 'Dinheiro de volta na conta.')}
        />
      )}
    </div>
  )
}

function CdiLine({ cdi }: { cdi: Cdi | null }) {
  return cdi ? (
    <p className="cdi">
      CDI de {percent.format(cdi.yearPercent)}% ao ano
      <span className="muted">
        {' '}
        pelo Banco Central, em {formatDay(cdi.day)}
      </span>
    </p>
  ) : (
    <p className="cdi muted">A taxa do Banco Central ainda não chegou. Os dias que faltarem são pagos depois.</p>
  )
}

/** Aquário: a água sobe conforme a meta. */
function Tank({ vault, total }: { vault: Vault; total: number }) {
  const { money, hidden } = usePrivacy()
  const level = waterLevel(vault.balanceCents, vault.goalCents, total)
  return (
    <div className="tank-glass" style={{ '--level': hidden ? 0.5 : level } as CSSProperties}>
      <svg className="tank-water" viewBox="0 0 400 100" preserveAspectRatio="none" aria-hidden="true">
        <path d="M0 14C50 4 50 4 100 14S150 24 200 14 250 4 300 14 350 24 400 14V100H0Z" />
      </svg>
      <div className="tank-text">
        <h2>{vault.name}</h2>
        <p className="tank-balance">{money(vault.balanceCents)}</p>
        <p>
          {vault.cdiPercent}% do CDI
          {vault.yieldCents > 0 && `, rendeu ${money(vault.yieldCents)}`}
        </p>
        {vault.goalCents !== null && (
          <p>
            Meta de {money(vault.goalCents)}
            {!hidden && `, ${Math.floor(level * 100)}%`}
          </p>
        )}
        {vault.lockedUntil && (
          <p>{vault.locked ? `Trancada até ${formatDay(vault.lockedUntil)}` : 'Liberada para resgate'}</p>
        )}
      </div>
    </div>
  )
}

function Simulator({ cdi, start }: { cdi: Cdi; start: number }) {
  const { money } = usePrivacy()
  const ids = { months: useId(), monthly: useId() }
  const [months, setMonths] = useState(12)
  const [monthly, setMonthly] = useState(100_00)
  const free = project(start, monthly, months, cdi.dailyPercent, 100)
  const locked = project(start, monthly, months, cdi.dailyPercent, 110)
  return (
    <section className="simulator" aria-labelledby="simulator-title">
      <h2 id="simulator-title">Se você continuar guardando</h2>
      <div className="simulator-controls">
        <label htmlFor={ids.monthly}>
          Por mês <strong>{formatCents(monthly)}</strong>
        </label>
        <input id={ids.monthly} type="range" min={0} max={2_000_00} step={50_00} value={monthly}
          aria-valuetext={formatCents(monthly)} onChange={(event) => setMonthly(Number(event.target.value))} />
        <label htmlFor={ids.months}>
          Durante <strong>{months === 1 ? '1 mês' : `${months} meses`}</strong>
        </label>
        <input id={ids.months} type="range" min={1} max={60} value={months}
          aria-valuetext={`${months} meses`} onChange={(event) => setMonths(Number(event.target.value))} />
      </div>
      <output htmlFor={`${ids.monthly} ${ids.months}`} className="simulator-result">
        <span>
          <span className="muted">Livre, 100% do CDI</span>
          <strong>{money(free.totalCents)}</strong>
          <span>{money(free.yieldCents)} de rendimento</span>
        </span>
        <span>
          <span className="muted">Trancada, 110% do CDI</span>
          <strong>{money(locked.totalCents)}</strong>
          <span>{money(locked.yieldCents)} de rendimento</span>
        </span>
      </output>
      <p className="muted">
        Simulação com o CDI de hoje e sem impostos. A taxa muda, e o dinheiro é fictício.
      </p>
    </section>
  )
}

function isoIn(days: number) {
  return new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10)
}

function NewVault({ onClose, onDone }: { onClose: () => void; onDone: (name: string) => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [name, setName] = useState('')
  const [goal, setGoal] = useState(0)
  const [locked, setLocked] = useState(false)
  const [until, setUntil] = useState(() => isoIn(180))
  const [error, setError] = useState('')
  const [sending, setSending] = useState(false)

  useEffect(() => dialog.current?.showModal(), [])

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (name.trim() === '') return setError('Dê um nome para a caixinha.')
    setSending(true)
    setError('')
    try {
      await createVault({ name: name.trim(), goalCents: goal || null, lockedUntil: locked ? until : null })
      onDone(name.trim())
    } catch (failure) {
      setError(messageOf(failure))
      setSending(false)
    }
  }

  return (
    <dialog ref={dialog} className="dialog" aria-labelledby="new-vault" onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <h2 id="new-vault">Criar caixinha</h2>
        <Field label="Nome" autoFocus autoComplete="off" maxLength={40} value={name}
          onChange={(event) => setName(event.target.value)} />
        <Field label="Meta" hint="Opcional." inputMode="numeric" autoComplete="off" value={goal ? formatCents(goal) : ''}
          placeholder="R$ 0,00" onChange={(event) => setGoal(centsFromInput(event.target.value))} />
        <fieldset className="choices">
          <legend>Tipo</legend>
          <label>
            <input type="radio" name="kind" checked={!locked} onChange={() => setLocked(false)} />
            <span>
              Livre <span className="muted">100% do CDI, resgate quando quiser</span>
            </span>
          </label>
          <label>
            <input type="radio" name="kind" checked={locked} onChange={() => setLocked(true)} />
            <span>
              Trancada <span className="muted">110% do CDI, só resgata na data</span>
            </span>
          </label>
        </fieldset>
        {locked && (
          <Field label="Trancar até" type="date" min={isoIn(31)} value={until}
            hint="De 30 dias a 5 anos. Não dá para abrir antes."
            onChange={(event) => setUntil(event.target.value)} />
        )}
        {error && <Alert>{error}</Alert>}
        <div className="dialog-actions">
          <button type="button" className="button quiet" onClick={() => dialog.current?.close()}>
            Cancelar
          </button>
          <button className="button" disabled={sending}>
            {sending ? 'Criando…' : 'Criar'}
          </button>
        </div>
      </form>
    </dialog>
  )
}

interface MoveProps {
  vault: Vault
  way: 'deposits' | 'withdrawals'
  available: number
  onClose: () => void
  onDone: () => void
}

function MoveDialog({ vault, way, available, onClose, onDone }: MoveProps) {
  const dialog = useRef<HTMLDialogElement>(null)
  const { money } = usePrivacy()
  // A chave acompanha o valor: repetir após uma queda de conexão não move duas vezes.
  const key = useRef(crypto.randomUUID())
  const [cents, setCents] = useState(0)
  const [error, setError] = useState('')
  const [sending, setSending] = useState(false)
  const saving = way === 'deposits'
  const verb = saving ? 'Guardar' : 'Resgatar'

  useEffect(() => dialog.current?.showModal(), [])

  const change = (value: number) => {
    if (value !== cents) key.current = crypto.randomUUID()
    setCents(value)
    setError('')
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (cents === 0) return setError('Digite um valor.')
    if (cents > available) return setError(saving ? 'Seu saldo não cobre este valor.' : 'A caixinha não tem tudo isso.')
    setSending(true)
    try {
      await moveVault(vault.id, way, cents, key.current)
      onDone()
    } catch (failure) {
      setError(messageOf(failure))
      setSending(false)
    }
  }

  return (
    <dialog ref={dialog} className="dialog" aria-labelledby="move-title" onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <h2 id="move-title">
          {verb} {saving ? 'em' : 'de'} {vault.name}
        </h2>
        <p className="muted">
          {saving ? 'Saldo na conta' : 'Guardado'}: {money(available)}
          {saving && vault.locked && vault.lockedUntil && `. Só sai em ${formatDay(vault.lockedUntil)}`}
        </p>
        <Field label="Valor" className="money-input" inputMode="numeric" autoComplete="off" autoFocus
          value={formatCents(cents)} onChange={(event) => change(centsFromInput(event.target.value))} />
        <div className="shortcuts">
          <button type="button" className="button quiet" disabled={available === 0} onClick={() => change(available)}>
            {saving ? 'Todo o saldo' : 'Tudo'}
          </button>
        </div>
        {error && <Alert>{error}</Alert>}
        <div className="dialog-actions">
          <button type="button" className="button quiet" onClick={() => dialog.current?.close()}>
            Cancelar
          </button>
          <button className="button" disabled={sending}>
            {sending ? 'Movendo…' : verb}
          </button>
        </div>
      </form>
    </dialog>
  )
}
