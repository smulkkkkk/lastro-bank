import { useCallback, useEffect, useRef, useState } from 'react'
import { liveFetch } from './api'

export type NoticeKind = 'PIX_RECEIVED' | 'PIX_SENT' | 'PIX_REVIEW' | 'PIX_DENIED' | 'PIX_CANCELLED' | 'SUPPORT'

export interface Notice {
  seq: number
  id: string
  kind: NoticeKind
  title: string
  body: string
  link?: string
  createdAt: string
  readAt: string | null
}

export interface SseEvent {
  id?: string
  event: string
  data: string
}

/**
 * Lê Server-Sent Events de um texto que chega em pedaços. Devolve os eventos completos e o resto,
 * que ainda espera o próximo pedaço. Linhas que começam com ":" são comentários (ping).
 */
export function parseSse(buffer: string): { events: SseEvent[]; rest: string } {
  const normalized = buffer.replace(/\r\n?/g, '\n')
  const blocks = normalized.split('\n\n')
  const rest = blocks.pop() ?? ''
  const events: SseEvent[] = []
  for (const block of blocks) {
    const event: SseEvent = { event: 'message', data: '' }
    const data: string[] = []
    for (const line of block.split('\n')) {
      if (line === '' || line.startsWith(':')) continue
      const colon = line.indexOf(':')
      const field = colon < 0 ? line : line.slice(0, colon)
      const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
      if (field === 'data') data.push(value)
      else if (field === 'event') event.event = value
      else if (field === 'id') event.id = value
    }
    if (data.length === 0 && event.event === 'message') continue
    event.data = data.join('\n')
    events.push(event)
  }
  return { events, rest }
}

const wait = (ms: number, signal: AbortSignal) =>
  new Promise<void>((resolve) => {
    const timer = setTimeout(resolve, ms)
    signal.addEventListener('abort', () => (clearTimeout(timer), resolve()), { once: true })
  })

/**
 * Avisos do cliente: a lista recente e uma conexão aberta com o sentinel que recebe os novos na
 * hora. Caiu? Reconecta com espera crescente e pede o que perdeu pelo Last-Event-ID.
 */
export function useNotices(onArrive: (notice: Notice) => void) {
  const [items, setItems] = useState<Notice[]>([])
  const [unread, setUnread] = useState(0)
  const [online, setOnline] = useState(false)
  const last = useRef(-1)
  const arrive = useRef(onArrive)
  arrive.current = onArrive

  useEffect(() => {
    const stop = new AbortController()
    const { signal } = stop

    const add = (notice: Notice) => {
      last.current = Math.max(last.current, notice.seq)
      setItems((current) => (current.some((n) => n.seq === notice.seq) ? current : [notice, ...current].slice(0, 30)))
      setUnread((count) => count + 1)
      arrive.current(notice)
    }

    void (async () => {
      let backoff = 2000
      while (!signal.aborted) {
        try {
          // Na primeira vez (e se a lista falhou antes), carrega a lista; depois, só o que faltou.
          if (last.current < 0) {
            const response = await liveFetch('/v1/notifications', { signal })
            if (!response.ok) throw new Error(String(response.status))
            const page: { items: Notice[]; unread: number } = await response.json()
            setItems(page.items)
            setUnread(page.unread)
            last.current = page.items[0]?.seq ?? 0
          }
          const response = await liveFetch('/v1/stream', {
            signal,
            headers: { 'Last-Event-ID': String(last.current), Accept: 'text/event-stream' },
          })
          if (!response.ok || !response.body) throw new Error(String(response.status))
          setOnline(true)
          backoff = 2000
          const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
          let buffer = ''
          for (;;) {
            const { value, done } = await reader.read()
            if (done) break
            const parsed = parseSse(buffer + value)
            buffer = parsed.rest
            for (const event of parsed.events) {
              if (event.event === 'notification') add(JSON.parse(event.data) as Notice)
            }
          }
        } catch {
          if (signal.aborted) return
        }
        setOnline(false)
        // Reconecta em 2 s; falhas seguidas esperam mais a cada vez, até 30 s.
        await wait(backoff, signal)
        backoff = Math.min(backoff * 2, 30000)
      }
    })()
    return () => stop.abort()
  }, [])

  const markAllRead = useCallback(async () => {
    const upTo = items[0]?.seq
    if (upTo === undefined || unread === 0) return
    setUnread(0)
    const now = new Date().toISOString()
    setItems((current) => current.map((n) => (n.readAt ? n : { ...n, readAt: now })))
    await liveFetch('/v1/notifications/read', { method: 'POST', body: JSON.stringify({ upTo }) }).catch(() => {})
  }, [items, unread])

  return { items, unread, online, markAllRead }
}
