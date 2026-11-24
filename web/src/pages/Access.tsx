import { useState, type FormEvent, type ReactNode } from 'react'
import { Link, Navigate, useNavigate } from 'react-router'
import { Alert, Brand, Field, ThemeToggle } from '../components/ui'
import { ApiError, register } from '../lib/api'
import { fictitiousCpf, formatCpf, isValidCpf } from '../lib/cpf'
import { messageOf, useSession } from '../lib/session'

function AccessLayout({ title, intro, children }: { title: string; intro: string; children: ReactNode }) {
  return (
    <div className="access">
      <header className="topbar">
        <Brand />
        <ThemeToggle />
      </header>
      <main className="access-panel">
        <h1>{title}</h1>
        <p className="muted">{intro}</p>
        {children}
      </main>
    </div>
  )
}

export function Login() {
  const session = useSession()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [sending, setSending] = useState(false)

  if (session.status === 'in') return <Navigate to="/app" replace />

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setError('')
    setSending(true)
    try {
      await session.login(email, password)
    } catch (failure) {
      setError(messageOf(failure))
      setSending(false)
    }
  }

  return (
    <AccessLayout title="Entrar" intro="Use o e-mail e a senha do seu cadastro.">
      <form onSubmit={submit}>
        <Field label="E-mail" type="email" autoComplete="username" required value={email}
          onChange={(event) => setEmail(event.target.value)} />
        <Field label="Senha" type="password" autoComplete="current-password" required value={password}
          onChange={(event) => setPassword(event.target.value)} />
        {error && <Alert>{error}</Alert>}
        <button className="button large wide" disabled={sending}>
          {sending ? 'Entrando…' : 'Entrar'}
        </button>
      </form>
      <p className="access-switch">
        Ainda não tem conta? <Link to="/criar-conta">Criar conta</Link>
      </p>
    </AccessLayout>
  )
}

export function Register() {
  const session = useSession()
  const navigate = useNavigate()
  const [form, setForm] = useState({ fullName: '', email: '', cpf: '', password: '' })
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [sending, setSending] = useState(false)

  if (session.status === 'in') return <Navigate to="/app" replace />

  const set = (name: keyof typeof form, value: string) => {
    setForm((current) => ({ ...current, [name]: value }))
    setFields(({ [name]: _, ...rest }) => rest)
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setError('')
    const invalid: Record<string, string> = {}
    if (form.fullName.trim().length < 3) invalid.fullName = 'Digite um nome com pelo menos 3 letras.'
    if (!/^\S+@\S+\.\S+$/.test(form.email.trim())) invalid.email = 'Digite um e-mail no formato nome@exemplo.com.'
    if (!isValidCpf(form.cpf)) invalid.cpf = 'Os dígitos deste CPF não conferem. Gere um fictício.'
    if (form.password.length < 10) invalid.password = 'A senha precisa de pelo menos 10 caracteres.'
    setFields(invalid)
    if (Object.keys(invalid).length > 0) return

    setSending(true)
    try {
      await register({ ...form, email: form.email.trim() })
    } catch (failure) {
      if (failure instanceof ApiError) setFields(failure.fields)
      setError(messageOf(failure))
      setSending(false)
      return
    }
    // A conta já existe: se só o login falhar, refazer o cadastro não adiantaria.
    try {
      await session.login(form.email, form.password)
    } catch {
      navigate('/entrar')
    }
  }

  return (
    <AccessLayout title="Criar conta" intro="Demonstração: invente os dados. Sua conta começa com R$ 1.000,00 fictícios.">
      <form onSubmit={submit} noValidate>
        <Field label="Nome" autoComplete="name" maxLength={120} value={form.fullName} error={fields.fullName}
          onChange={(event) => set('fullName', event.target.value)} />
        <Field label="E-mail" type="email" autoComplete="username" maxLength={254} value={form.email}
          error={fields.email} hint="Pode ser inventado, nada é enviado."
          onChange={(event) => set('email', event.target.value)} />
        <Field label="CPF" inputMode="numeric" autoComplete="off" value={form.cpf} error={fields.cpf}
          onChange={(event) => set('cpf', formatCpf(event.target.value))}
          action={
            <button type="button" className="link-button" onClick={() => set('cpf', fictitiousCpf())}>
              Gerar CPF fictício
            </button>
          } />
        <Field label="Senha" type="password" autoComplete="new-password" maxLength={128} value={form.password}
          error={fields.password} hint="Pelo menos 10 caracteres."
          onChange={(event) => set('password', event.target.value)} />
        {error && <Alert>{error}</Alert>}
        <button className="button large wide" disabled={sending}>
          {sending ? 'Criando conta…' : 'Criar conta'}
        </button>
      </form>
      <p className="access-switch">
        Já tem conta? <Link to="/entrar">Entrar</Link>
      </p>
    </AccessLayout>
  )
}
