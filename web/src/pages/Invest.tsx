import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { formatDate } from '../components/EntryRow'
import { PriceChart } from '../components/PriceChart'
import { Alert, Field } from '../components/ui'
import {
  ApiError,
  buyAsset,
  fetchHistory,
  fetchMarket,
  fetchNews,
  fetchPortfolio,
  sellAsset,
  type Market,
  type MarketAsset,
  type News,
  type Portfolio,
  type PricePoint,
} from '../lib/api'
import { formatPercent, formatPrice, formatQuantity, unitsFor } from '../lib/invest'
import { centsFromInput, formatCents } from '../lib/money'
import { usePrivacy } from '../lib/privacy'
import { messageOf } from '../lib/session'
import { useBank } from './AppShell'

const ago = new Intl.RelativeTimeFormat('pt-BR', { numeric: 'auto' })
function since(iso: string) {
  const minutes = Math.round((Date.parse(iso) - Date.now()) / 60_000)
  return Math.abs(minutes) < 60 ? ago.format(minutes, 'minute') : ago.format(Math.round(minutes / 60), 'hour')
}

export function Invest() {
  const { account, reload } = useBank()
  const { money } = usePrivacy()
  const [market, setMarket] = useState<Market>()
  const [portfolio, setPortfolio] = useState<Portfolio>()
  const [news, setNews] = useState<News[]>([])
  const [open, setOpen] = useState<MarketAsset>()
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  const load = useCallback(async () => {
    setError('')
    try {
      const [prices, wallet] = await Promise.all([fetchMarket(), fetchPortfolio()])
      setMarket(prices)
      setPortfolio(wallet)
    } catch (failure) {
      setError(messageOf(failure))
    }
  }, [])

  useEffect(() => {
    void load()
    fetchNews().then(setNews, () => setNews([]))
    // Cotação velha engana: atualiza a cada minuto enquanto a tela está aberta.
    const timer = setInterval(() => {
      if (!document.hidden) fetchMarket().then(setMarket, () => {})
    }, 60_000)
    return () => clearInterval(timer)
  }, [load])

  if (!market || !portfolio) {
    return error ? (
      <div className="state">
        <Alert>{error}</Alert>
        <button type="button" className="button" onClick={() => void load()}>
          Tentar de novo
        </button>
      </div>
    ) : (
      <p className="state muted" role="status">
        Buscando cotações…
      </p>
    )
  }

  const result = portfolio.resultCents
  return (
    <div className="invest">
      <section className="summary" aria-label="Seus investimentos">
        <h1 className="balance-label">Seus investimentos</h1>
        <p className="balance">{money(portfolio.valueCents)}</p>
        {portfolio.positions.length > 0 && (
          <p className={result >= 0 ? 'result up' : 'result down'}>
            {result >= 0 ? '+' : '−'} {money(Math.abs(result))} desde a compra
          </p>
        )}
        <p className="notice" role="status">
          {notice}
        </p>
      </section>

      {market.stale && (
        <Alert>A fonte de cotações não respondeu agora. Os preços abaixo são de {since(market.updatedAt)}.</Alert>
      )}

      {portfolio.positions.length > 0 && (
        <section aria-labelledby="positions-title">
          <h2 id="positions-title" className="section-title">
            O que você tem
          </h2>
          <ul className="positions">
            {portfolio.positions.map((position) => (
              <li key={position.asset}>
                <span>
                  <strong>{position.name}</strong>
                  <span className="muted">{formatQuantity(position.quantity, position.asset)}</span>
                </span>
                <span className="position-values">
                  <strong>{money(position.valueCents)}</strong>
                  <span className={position.resultCents >= 0 ? 'result up' : 'result down'}>
                    {position.resultCents >= 0 ? '+' : '−'} {money(Math.abs(position.resultCents))}
                  </span>
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section aria-labelledby="options-title">
        <h2 id="options-title" className="section-title">
          No que dá para investir
        </h2>
        <ul className="assets">
          <li>
            <Link to="/app/caixinhas" className="asset-card">
              <span className="asset-top">
                <strong>Caixinhas</strong>
                <span className="muted">Renda fixa</span>
              </span>
              <span className="asset-price plain">100% do CDI</span>
              <span className="muted">Risco baixo. Rende todo dia útil.</span>
            </Link>
          </li>
          {market.assets.map((asset) => (
            <li key={asset.code}>
              <button type="button" className="asset-card" onClick={() => setOpen(asset)}>
                <span className="asset-top">
                  <strong>{asset.name}</strong>
                  <span className="muted">{asset.category}</span>
                </span>
                <span className="asset-price">{formatPrice(asset.bid)}</span>
                <span className={asset.changePercent >= 0 ? 'result up' : 'result down'}>
                  {formatPercent(asset.changePercent)} em 24 horas
                </span>
                <span className="muted">Risco {asset.risk.toLowerCase()}</span>
              </button>
            </li>
          ))}
        </ul>
        <p className="muted small">
          Cotações reais pela AwesomeAPI, dinheiro fictício. Nada aqui é recomendação de investimento.
        </p>
      </section>

      {news.length > 0 && (
        <section aria-labelledby="news-title">
          <h2 id="news-title" className="section-title">
            Notícias do mercado
          </h2>
          <ul className="news">
            {news.map((item) => (
              <li key={item.link}>
                <a href={item.link} target="_blank" rel="noopener noreferrer">
                  {item.title}
                </a>
                <span className="muted">
                  {item.source}, {since(item.publishedAt)}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}

      {portfolio.trades.length > 0 && (
        <section aria-labelledby="trades-title">
          <h2 id="trades-title" className="section-title">
            Histórico de ordens
          </h2>
          <ul className="positions">
            {portfolio.trades.map((trade) => (
              <li key={trade.id}>
                <span>
                  <strong>
                    {trade.side === 'BUY' ? 'Compra' : 'Venda'} de {formatQuantity(trade.quantity, trade.asset)}
                  </strong>
                  <span className="muted">
                    {formatDate(trade.createdAt)}, a {formatPrice(trade.price)}
                  </span>
                </span>
                <strong>{money(trade.amountCents)}</strong>
              </li>
            ))}
          </ul>
        </section>
      )}

      {open && (
        <AssetDialog
          asset={market.assets.find((item) => item.code === open.code) ?? open}
          held={portfolio.positions.find((item) => item.asset === open.code)}
          available={account.availableCents}
          onClose={() => setOpen(undefined)}
          onDone={(message) => {
            setOpen(undefined)
            setNotice(message)
            void load()
            void reload()
          }}
        />
      )}
    </div>
  )
}

interface DialogProps {
  asset: MarketAsset
  held: Portfolio['positions'][number] | undefined
  available: number
  onClose: () => void
  onDone: (message: string) => void
}

function AssetDialog({ asset, held, available, onClose, onDone }: DialogProps) {
  const dialog = useRef<HTMLDialogElement>(null)
  const { money } = usePrivacy()
  const key = useRef(crypto.randomUUID())
  const [days, setDays] = useState(30)
  const [points, setPoints] = useState<PricePoint[]>()
  const [side, setSide] = useState<'BUY' | 'SELL'>('BUY')
  const [cents, setCents] = useState(100_00)
  const [error, setError] = useState('')
  const [sending, setSending] = useState(false)

  useEffect(() => dialog.current?.showModal(), [])
  useEffect(() => {
    setPoints(undefined)
    fetchHistory(asset.code, days).then(setPoints, () => setPoints([]))
  }, [asset.code, days])

  const price = side === 'BUY' ? asset.ask : asset.bid
  const change = () => {
    key.current = crypto.randomUUID()
    setError('')
  }

  const submit = async (all: boolean) => {
    if (!all && cents === 0) return setError('Digite um valor.')
    if (side === 'BUY' && cents > available) return setError('Seu saldo não cobre este valor.')
    setSending(true)
    try {
      const trade = side === 'BUY'
        ? await buyAsset(asset.code, cents, asset.ask, key.current)
        : await sellAsset(asset.code, all ? null : cents, all, asset.bid, key.current)
      onDone(`${trade.side === 'BUY' ? 'Compra' : 'Venda'} de ${formatQuantity(trade.quantity, trade.asset)} por ${formatCents(trade.amountCents)}.`)
    } catch (failure) {
      setError(failure instanceof ApiError && failure.code === 'invest.price_changed'
        ? 'O preço mudou desde que você abriu. Feche e abra de novo para ver o novo valor.'
        : messageOf(failure))
      setSending(false)
    }
  }

  return (
    <dialog ref={dialog} className="dialog asset-dialog" aria-labelledby="asset-title" onClose={onClose}>
      <form onSubmit={(event: FormEvent) => { event.preventDefault(); void submit(false) }} noValidate>
        <div className="asset-head">
          <h2 id="asset-title">{asset.name}</h2>
          <span className={asset.changePercent >= 0 ? 'result up' : 'result down'}>{formatPercent(asset.changePercent)}</span>
        </div>
        <p className="muted">{asset.description}</p>
        <div className="segmented" role="radiogroup" aria-label="Período do gráfico">
          {[7, 30, 90].map((value) => (
            <button key={value} type="button" role="radio" aria-checked={days === value} onClick={() => setDays(value)}>
              {value} dias
            </button>
          ))}
        </div>
        {points ? <PriceChart points={points} name={asset.name} /> : <p className="muted chart-wait">Carregando gráfico…</p>}

        <div className="segmented" role="radiogroup" aria-label="Comprar ou vender">
          <button type="button" role="radio" aria-checked={side === 'BUY'} onClick={() => { change(); setSide('BUY') }}>
            Comprar a {formatPrice(asset.ask)}
          </button>
          <button type="button" role="radio" aria-checked={side === 'SELL'} disabled={!held}
            onClick={() => { change(); setSide('SELL') }}>
            Vender a {formatPrice(asset.bid)}
          </button>
        </div>
        <Field label="Valor" className="money-input" inputMode="numeric" autoComplete="off" value={formatCents(cents)}
          hint={side === 'BUY'
            ? `Compra cerca de ${formatQuantity(String(unitsFor(cents, price, asset.code)), asset.code)}. Saldo: ${money(available)}.`
            : `Você tem ${held ? `${formatQuantity(held.quantity, asset.code)}, ${money(held.valueCents)}` : 'nada'}.`}
          onChange={(event) => { change(); setCents(centsFromInput(event.target.value)) }} />
        {error && <Alert>{error}</Alert>}
        <div className="dialog-actions">
          <button type="button" className="button quiet" onClick={() => dialog.current?.close()}>
            Fechar
          </button>
          {side === 'SELL' && held && (
            <button type="button" className="button quiet" disabled={sending} onClick={() => void submit(true)}>
              Vender tudo
            </button>
          )}
          <button className="button" disabled={sending}>
            {sending ? 'Enviando…' : side === 'BUY' ? 'Comprar' : 'Vender'}
          </button>
        </div>
        <p className="muted small">Se o preço andar mais de 1% antes da ordem chegar, ela é recusada e nada muda.</p>
      </form>
    </dialog>
  )
}
