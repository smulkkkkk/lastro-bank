import { useId, useState, type InputHTMLAttributes, type ReactNode } from 'react'
import { Link } from 'react-router'
import { FishMark, Wordmark } from './Marks'

export function Brand({ to = '/' }: { to?: string }) {
  return (
    <Link to={to} className="brand" aria-label="Lastro, início">
      <FishMark />
      <Wordmark />
    </Link>
  )
}

export function ThemeToggle() {
  const [theme, setTheme] = useState(document.documentElement.dataset.theme ?? 'light')
  const next = theme === 'dark' ? 'light' : 'dark'
  return (
    <button
      type="button"
      className="icon-button"
      aria-label={next === 'light' ? 'Usar tema claro' : 'Usar tema escuro'}
      onClick={() => {
        document.documentElement.dataset.theme = next
        try {
          localStorage.setItem('lastro-tema', next)
        } catch {
          // Sem armazenamento, o tema vale só até fechar a página.
        }
        setTheme(next)
      }}
    >
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <circle cx="12" cy="12" r="8" />
        <path d="M12 4a8 8 0 0 1 0 16z" fill="currentColor" stroke="none" />
      </svg>
    </button>
  )
}

interface FieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string
  hint?: string
  error?: string
  action?: ReactNode
}

export function Field({ label, hint, error, action, ...input }: FieldProps) {
  const id = useId()
  const note = error ?? hint
  return (
    <div className="field">
      <div className="field-head">
        <label htmlFor={id}>{label}</label>
        {action}
      </div>
      <input id={id} aria-invalid={!!error} aria-describedby={note ? `${id}-note` : undefined} {...input} />
      {note && (
        <p id={`${id}-note`} className={error ? 'field-note error' : 'field-note'}>
          {note}
        </p>
      )}
    </div>
  )
}

interface SwitchProps {
  label: string
  hint: string
  checked: boolean
  disabled?: boolean
  onChange: (checked: boolean) => void
}

export function Switch({ label, hint, checked, disabled, onChange }: SwitchProps) {
  const id = useId()
  return (
    <div className="switch-row">
      <div>
        <span id={id}>{label}</span>
        <p className="muted">{hint}</p>
      </div>
      <button
        type="button"
        role="switch"
        className="switch"
        aria-checked={checked}
        aria-labelledby={id}
        disabled={disabled}
        onClick={() => onChange(!checked)}
      />
    </div>
  )
}

export function Alert({ children }: { children: ReactNode }) {
  return (
    <p className="alert" role="alert">
      {children}
    </p>
  )
}
