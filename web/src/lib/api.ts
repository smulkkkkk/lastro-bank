export interface Account {
  id: string
  kind: 'CHECKING' | 'VAULT'
  branch: string
  number: string
  balanceCents: number
  heldCents: number
  availableCents: number
}

export interface Entry {
  entryId: number
  transactionId: string
  kind: string
  description: string
  direction: 'DEBIT' | 'CREDIT'
  amountCents: number
  balanceAfterCents: number
  createdAt: string
}

export interface StatementPage {
  items: Entry[]
  nextCursor: number | null
}

export interface StatementQuery {
  kind?: string
  from?: string
  to?: string
  before?: number
  limit?: number
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly fields: Record<string, string> = {},
  ) {
    super(message)
  }
}

// O token de acesso fica só em memória: um script injetado não o encontra no armazenamento.
let accessToken: string | null = null
// Apenas indica que vale a pena tentar renovar ao abrir o site. Não dá acesso a nada.
const HINT = 'lastro-sessao'
let refreshing: Promise<boolean> | null = null
let onSessionEnd: () => void = () => {}

export function setSessionEndHandler(handler: () => void) {
  onSessionEnd = handler
}

function remember(active: boolean) {
  try {
    if (active) localStorage.setItem(HINT, '1')
    else localStorage.removeItem(HINT)
  } catch {
    // Armazenamento bloqueado: a sessão só não é retomada ao reabrir.
  }
}

function hasHint() {
  try {
    return localStorage.getItem(HINT) === '1'
  } catch {
    return false
  }
}

async function send(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers)
  if (init.body) headers.set('Content-Type', 'application/json')
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
  try {
    return await fetch(`/api/v1${path}`, { ...init, headers, credentials: 'same-origin' })
  } catch {
    throw new ApiError(0, 'network', 'Sem conexão com o banco. Confira se a API está rodando.')
  }
}

async function failure(response: Response): Promise<ApiError> {
  const body = await response.json().catch(() => null)
  if (!body?.code) {
    return new ApiError(response.status, 'unavailable', 'O banco não respondeu. Tente de novo em instantes.')
  }
  return new ApiError(response.status, body.code, body.detail, body.fields)
}

/**
 * Cada cookie de renovação vale uma vez, e reutilizar derruba a sessão. A trava garante que duas
 * abas não usem o mesmo cookie ao mesmo tempo.
 */
function refresh(): Promise<boolean> {
  refreshing ??= (async () => {
    const attempt = async () => {
      const response = await send('/auth/refresh', { method: 'POST' })
      if (response.ok) {
        accessToken = (await response.json()).accessToken
        return true
      }
      // Só 401 encerra a sessão; banco fora do ar não é motivo para deslogar ninguém.
      if (response.status !== 401) throw await failure(response)
      accessToken = null
      remember(false)
      return false
    }
    return navigator.locks ? navigator.locks.request('lastro-refresh', attempt) : attempt()
  })().finally(() => {
    refreshing = null
  })
  return refreshing
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  let response = await send(path, init)
  if (response.status === 401) {
    if (await refresh()) response = await send(path, init)
    if (response.status === 401) onSessionEnd()
  }
  if (!response.ok) throw await failure(response)
  return response.json()
}

export async function resumeSession(): Promise<boolean> {
  return hasHint() ? refresh() : false
}

export async function login(email: string, password: string) {
  accessToken = null
  const response = await send('/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) })
  if (!response.ok) throw await failure(response)
  accessToken = (await response.json()).accessToken
  remember(true)
}

export async function register(data: { fullName: string; email: string; cpf: string; password: string }) {
  accessToken = null
  const response = await send('/auth/register', { method: 'POST', body: JSON.stringify(data) })
  if (!response.ok) throw await failure(response)
}

export async function logout() {
  const response = await send('/auth/logout', { method: 'POST' })
  if (!response.ok) throw await failure(response)
  accessToken = null
  remember(false)
}

export const fetchAccounts = () => call<Account[]>('/accounts')

export const deposit = (accountId: string, amountCents: number, idempotencyKey: string) =>
  call<{ transactionId: string }>(`/accounts/${accountId}/deposits`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ amountCents }),
  })

export function fetchStatement(accountId: string, query: StatementQuery = {}) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== '') params.set(key, String(value))
  }
  return call<StatementPage>(`/accounts/${accountId}/statement?${params}`)
}
