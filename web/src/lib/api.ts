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

export type CardDesign = 'ONIX' | 'MARE' | 'BRASA'

export interface Card {
  id: string
  design: CardDesign
  label: string
  last4: string
  primary: boolean
  frozen: boolean
  contactless: boolean
}

export type CardChange = { frozen: boolean } | { contactless: boolean } | { primary: true }

export interface TracePart {
  entryId: number
  kind: string
  description: string
  createdAt: string
  amountCents: number
  partCents: number
}

export interface Trace {
  direction: 'DEBIT' | 'CREDIT'
  amountCents: number
  remainingCents: number
  items: TracePart[]
}

export type PixKeyKind = 'EMAIL' | 'CPF' | 'RANDOM'

export interface PixKey {
  id: string
  kind: PixKeyKind
  value: string
}

export interface PixRecipient {
  key: string
  name: string
  maskedCpf: string
}

export interface PixContact {
  key: string
  name: string
}

export interface PixLimits {
  availableCents: number
  limitCents: number
  night: boolean
}

export interface PixReceipt {
  id: string
  key: string
  recipientName: string
  amountCents: number
  message: string | null
  createdAt: string
  status: PixStatus
}

export type PixStatus = 'PENDING' | 'UNDER_REVIEW' | 'SETTLED' | 'DENIED' | 'CANCELLED'

export interface ReviewItem {
  id: string
  amountCents: number
  createdAt: string
  senderName: string
  recipientName: string
  score: number | null
  reasons: { code: string; points: number; text: string }[]
}

export interface Cdi {
  day: string
  dailyPercent: number
  yearPercent: number
}

export interface Vault {
  id: string
  name: string
  goalCents: number | null
  cdiPercent: number
  lockedUntil: string | null
  locked: boolean
  balanceCents: number
  yieldCents: number
}

export interface VaultOverview {
  cdi: Cdi | null
  totalCents: number
  items: Vault[]
}

export interface Merchant {
  code: string
  name: string
  category: string
}

export interface Purchase {
  id: string
  cardId: string | null
  kind: 'PURCHASE' | 'CONVERSION'
  mode: 'DEBIT' | 'CREDIT'
  merchant: string
  category: string
  amountCents: number
  installments: number
  contactless: boolean
  createdAt: string
  discountCents: number
  device: 'CARD' | 'PHONE' | 'WATCH' | null
}

export interface Invoice {
  reference: string
  closesOn: string
  dueOn: string
  status: 'OPEN' | 'CLOSED' | 'OVERDUE' | 'PAID' | 'FUTURE'
  totalCents: number
  paidCents: number
  remainingCents: number
}

export interface InvoiceItem {
  purchaseId: string
  merchant: string
  category: string
  number: number
  installments: number
  amountCents: number
  createdAt: string
}

export interface CreditOverview {
  limitCents: number
  usedCents: number
  availableCents: number
  blocked: boolean
  invoices: Invoice[]
}

export interface ConversionQuote {
  amountCents: number
  installments: number
  feeCents: number
  totalCents: number
  firstInstallmentCents: number
  otherInstallmentsCents: number
}

export interface LoanQuote {
  amountCents: number
  installments: number
  installmentCents: number
  lastInstallmentCents: number
  interestCents: number
  totalCents: number
  firstDueOn: string
}

export interface LoanInstallment {
  number: number
  dueOn: string
  principalCents: number
  interestCents: number
  paidOn: string | null
  paidCents: number | null
  overdue: boolean
}

export interface Loan {
  id: string
  principalCents: number
  monthlyPercent: number
  createdOn: string
  settledOn: string | null
  nextCents: number | null
  settleCents: number | null
  installments: LoanInstallment[]
}

export interface LoanOverview {
  offer: {
    minCents: number
    maxCents: number
    minInstallments: number
    maxInstallments: number
    monthlyPercent: number
    yearPercent: number
  }
  active: Loan | null
}

export interface MarketAsset {
  code: string
  name: string
  category: string
  risk: string
  description: string
  bid: number
  ask: number
  changePercent: number
}

export interface Market {
  stale: boolean
  updatedAt: string
  assets: MarketAsset[]
}

export interface PricePoint {
  day: string
  price: number
}

export interface News {
  title: string
  link: string
  source: string
  publishedAt: string
}

export interface Trade {
  id: string
  asset: string
  side: 'BUY' | 'SELL'
  quantity: string
  price: number
  amountCents: number
  createdAt: string
}

export interface Portfolio {
  valueCents: number
  costCents: number
  resultCents: number
  positions: { asset: string; name: string; quantity: string; costCents: number; valueCents: number; resultCents: number }[]
  trades: Trade[]
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
      const response = await send('/auth/refresh', { method: 'POST', keepalive: true })
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
  return response.status === 204 ? (undefined as T) : response.json()
}

/**
 * Para o sentinel (avisos ao vivo), atrás do proxy /live. Mesmo token de acesso, mandado no
 * cabeçalho e nunca na URL; se vencer, renova uma vez e tenta de novo.
 */
export async function liveFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const attempt = () => {
    const headers = new Headers(init.headers)
    if (init.body) headers.set('Content-Type', 'application/json')
    if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
    return fetch(`/live${path}`, { ...init, headers, credentials: 'same-origin' })
  }
  let response = await attempt()
  if (response.status === 401 && (await refresh())) response = await attempt()
  return response
}

export interface HelpMessage {
  id: number
  role: 'USER' | 'ASSISTANT'
  text: string
  source: 'CLAUDE' | 'FAQ' | null
  createdAt: string
}

export interface HelpConversation {
  id: string | null
  status: 'NEW' | 'OPEN' | 'HANDED_OFF'
  protocol: string | null
  messages: HelpMessage[]
}

export const fetchConversation = () => call<HelpConversation>('/help/conversation')

export const sendHelpMessage = (text: string) =>
  call<HelpConversation>('/help/messages', { method: 'POST', body: JSON.stringify({ text }) })

export const askForPerson = (phone: string) =>
  call<HelpConversation>('/help/handoff', { method: 'POST', body: JSON.stringify({ phone }) })

export interface BoletoInfo {
  line: string
  bank: string
  beneficiary: string
  amountCents: number
  dueDate: string
  paid: boolean
}

export interface BillPayment {
  id: string
  kind: 'BOLETO' | 'TOP_UP'
  description: string
  amountCents: number
  createdAt: string
}

export interface BillReceipt extends BillPayment {
  payerName: string
  payerAccount: string
  recipient: string
  line: string | null
  dueDate: string | null
  phone: string | null
  authentication: string
}

export const fetchReceipt = (id: string) => call<BillReceipt>(`/bills/${id}`)

export type OperatorCode = 'ONDA' | 'FAROL' | 'CORRENTE'

export interface Operator {
  code: OperatorCode
  name: string
  amountsCents: number[]
}

export const lookupBoleto = (line: string) =>
  call<BoletoInfo>('/bills/boletos/lookup', { method: 'POST', body: JSON.stringify({ line }) })

export const payBoleto = (line: string, idempotencyKey: string) =>
  call<BillReceipt>('/bills/boletos/pay', withKey(idempotencyKey, { line }))

export const sampleBoleto = () => call<BoletoInfo>('/bills/boletos/sample', { method: 'POST', body: '{}' })

export const fetchOperators = () => call<Operator[]>('/bills/operators')

export const topUp = (operator: OperatorCode, phone: string, amountCents: number, idempotencyKey: string) =>
  call<BillReceipt>('/bills/top-ups', withKey(idempotencyKey, { operator, phone, amountCents }))

export const fetchBills = () => call<BillPayment[]>('/bills')

export interface BubbleEntry {
  bubbles: number
  reason: 'CASHBACK' | 'REDEEM'
  description: string
  createdAt: string
}

export interface BubbleWallet {
  bubbles: number
  valueCents: number
  entries: BubbleEntry[]
}

export interface Store {
  merchant: string
  name: string
  category: string
  blurb: string
  cashbackPercent: number
  couponPercent: number
  couponCapCents: number
  couponMinCents: number
  coupon: { id: string; expiresAt: string } | null
}

export const fetchBubbles = () => call<BubbleWallet>('/rewards')

export const fetchStores = () => call<Store[]>('/rewards/stores')

export const claimCoupon = (merchant: string) =>
  call<{ id: string; expiresAt: string }>(`/rewards/stores/${merchant}/coupon`, { method: 'POST', body: '{}' })

export const redeemBubbles = (bubbles: number, idempotencyKey: string) =>
  call<BubbleWallet>('/rewards/redeem', withKey(idempotencyKey, { bubbles }))

export type Permission = 'BALANCES' | 'TRANSACTIONS' | 'PAYMENTS'

export interface ExternalBank {
  code: string
  name: string
}

export interface Consent {
  id: string
  bankCode: string
  bankName: string
  permissions: Permission[]
  status: 'AWAITING' | 'AUTHORIZED' | 'REJECTED' | 'REVOKED' | 'EXPIRED'
  months: number
  createdAt: string
  expiresAt: string | null
  account: { branch: string; number: string; balanceCents: number | null } | null
}

export interface ExternalTransaction {
  at: string
  description: string
  amountCents: number
}

export const fetchBanks = () => call<ExternalBank[]>('/open-finance/banks')

export const fetchConsents = () => call<Consent[]>('/open-finance/consents')

export const requestConsent = (bank: string, permissions: Permission[], months: number) =>
  call<Consent>('/open-finance/consents', { method: 'POST', body: JSON.stringify({ bank, permissions, months }) })

export const authorizeConsent = (id: string, approve: boolean) =>
  call<Consent>(`/open-finance/consents/${id}/authorization`, { method: 'POST', body: JSON.stringify({ approve }) })

export const revokeConsent = (id: string) => call<Consent>(`/open-finance/consents/${id}`, { method: 'DELETE' })

export const fetchExternalTransactions = (id: string) =>
  call<ExternalTransaction[]>(`/open-finance/consents/${id}/transactions`)

export const bringMoney = (id: string, amountCents: number, idempotencyKey: string) =>
  call<{ id: string; amountCents: number; remainingCents: number }>(
    `/open-finance/consents/${id}/transfers`,
    withKey(idempotencyKey, { amountCents }),
  )

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

export const fetchCards = () => call<Card[]>('/cards')

export const updateCard = (id: string, change: CardChange) =>
  call<Card>(`/cards/${id}`, { method: 'PATCH', body: JSON.stringify(change) })

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

export const fetchTrace = (accountId: string, entryId: number) =>
  call<Trace>(`/accounts/${accountId}/entries/${entryId}/trace`)

export const fetchVaults = () => call<VaultOverview>('/vaults')

export const createVault = (data: { name: string; goalCents: number | null; lockedUntil: string | null }) =>
  call<Vault>('/vaults', { method: 'POST', body: JSON.stringify(data) })

export const moveVault = (id: string, way: 'deposits' | 'withdrawals', amountCents: number, idempotencyKey: string) =>
  call<Vault>(`/vaults/${id}/${way}`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ amountCents }),
  })

export const deleteVault = (id: string) => call<void>(`/vaults/${id}`, { method: 'DELETE' })

const withKey = (idempotencyKey: string, body: unknown): RequestInit => ({
  method: 'POST',
  headers: { 'Idempotency-Key': idempotencyKey },
  body: JSON.stringify(body),
})

export const fetchMerchants = () => call<Merchant[]>('/merchants')

export const fetchPurchases = () => call<Purchase[]>('/purchases')

export const buy = (
  cardId: string,
  data: { merchant: string; amountCents: number; mode: 'DEBIT' | 'CREDIT'; installments: number; contactless: boolean; device?: 'CARD' | 'PHONE' | 'WATCH' },
  idempotencyKey: string,
) => call<Purchase>(`/cards/${cardId}/purchases`, withKey(idempotencyKey, data))

export const fetchCredit = () => call<CreditOverview>('/credit')

export const fetchInvoiceItems = (month: string) => call<InvoiceItem[]>(`/credit/invoices/${month}/items`)

export const payInvoice = (month: string, amountCents: number, idempotencyKey: string) =>
  call<CreditOverview>(`/credit/invoices/${month}/payments`, withKey(idempotencyKey, { amountCents }))

export const fetchConversionQuote = (amountCents: number, installments: number) =>
  call<ConversionQuote>(`/credit/conversion?amountCents=${amountCents}&installments=${installments}`)

export const convertLimit = (amountCents: number, installments: number, acceptedTotalCents: number, idempotencyKey: string) =>
  call<Purchase>('/credit/conversions', withKey(idempotencyKey, { amountCents, installments, acceptedTotalCents }))

export const fetchLoans = () => call<LoanOverview>('/loans')

export const fetchLoanQuote = (amountCents: number, installments: number) =>
  call<LoanQuote>(`/loans/quote?amountCents=${amountCents}&installments=${installments}`)

export const takeLoan = (amountCents: number, installments: number, acceptedTotalCents: number, idempotencyKey: string) =>
  call<Loan>('/loans', withKey(idempotencyKey, { amountCents, installments, acceptedTotalCents }))

export const payLoan = (id: string, kind: 'NEXT' | 'ALL', expectedCents: number, idempotencyKey: string) =>
  call<Loan>(`/loans/${id}/payments`, withKey(idempotencyKey, { kind, expectedCents }))

export const fetchMarket = () => call<Market>('/market')

export const fetchHistory = (asset: string, days: number) => call<PricePoint[]>(`/market/${asset}/history?days=${days}`)

export const fetchNews = () => call<News[]>('/market/news')

export const fetchPortfolio = () => call<Portfolio>('/invest')

export const buyAsset = (asset: string, amountCents: number, expectedPrice: number, idempotencyKey: string) =>
  call<Trade>('/invest/buy', withKey(idempotencyKey, { asset, amountCents, expectedPrice }))

export const sellAsset = (asset: string, amountCents: number | null, all: boolean, expectedPrice: number, idempotencyKey: string) =>
  call<Trade>('/invest/sell', withKey(idempotencyKey, { asset, amountCents, all, expectedPrice }))

export const fetchPixStatus = (id: string) => call<PixReceipt>(`/pix/transfers/${id}`)

export const fetchReviews = () => call<ReviewItem[]>('/backoffice/reviews')

export const decideReview = (id: string, decision: 'APPROVE' | 'DENY', reason: string) =>
  call<void>(`/backoffice/reviews/${id}`, { method: 'POST', body: JSON.stringify({ decision, reason }) })

/** Papel do token atual. Serve só para mostrar ou esconder telas; quem decide acesso é o servidor. */
export function currentRole(): string | null {
  const payload = accessToken?.split('.')[1]
  if (!payload) return null
  try {
    return JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/'))).role ?? null
  } catch {
    return null
  }
}

export const fetchPixKeys = () => call<PixKey[]>('/pix/keys')

export const createPixKey = (kind: PixKeyKind) =>
  call<PixKey>('/pix/keys', { method: 'POST', body: JSON.stringify({ kind }) })

export const deletePixKey = (id: string) => call<void>(`/pix/keys/${id}`, { method: 'DELETE' })

export const lookupPixKey = (key: string) =>
  call<PixRecipient>('/pix/lookup', { method: 'POST', body: JSON.stringify({ key }) })

export const fetchPixLimits = () => call<PixLimits>('/pix/limits')

export const fetchPixContacts = () => call<PixContact[]>('/pix/contacts')

export const sendPix = (key: string, amountCents: number, message: string, idempotencyKey: string) =>
  call<PixReceipt>('/pix/transfers', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ key, amountCents, message: message.trim() || null }),
  })
