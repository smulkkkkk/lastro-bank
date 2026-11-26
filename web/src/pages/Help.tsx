import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Lino } from '../components/Lino'
import { Alert } from '../components/ui'
import { askForPerson, fetchConversation, sendHelpMessage, type HelpConversation } from '../lib/api'
import { messageOf } from '../lib/session'

const SUGGESTIONS = ['Qual o meu saldo?', 'Como rende a caixinha?', 'Meu Pix ficou em revisão', 'Qual o limite do Pix à noite?']

const hour = new Intl.DateTimeFormat('pt-BR', { hour: '2-digit', minute: '2-digit' })

export function Help() {
  const [conversation, setConversation] = useState<HelpConversation>()
  const [text, setText] = useState('')
  const [waiting, setWaiting] = useState(false)
  const [error, setError] = useState('')
  const [asking, setAsking] = useState(false)
  const [phone, setPhone] = useState('')
  const end = useRef<HTMLDivElement>(null)

  useEffect(() => {
    fetchConversation().then(setConversation, (failure) => setError(messageOf(failure)))
  }, [])

  useEffect(() => {
    end.current?.scrollIntoView({ block: 'end', behavior: 'smooth' })
  }, [conversation?.messages.length, waiting])

  async function send(message: string) {
    const clean = message.trim()
    if (!clean || waiting) return
    setError('')
    setWaiting(true)
    setText('')
    // Mostra a mensagem na hora; a resposta do servidor traz a conversa inteira.
    setConversation((current) => ({
      id: current?.id ?? null,
      status: current?.status === 'HANDED_OFF' ? 'OPEN' : (current?.status ?? 'OPEN'),
      protocol: current?.status === 'HANDED_OFF' ? null : (current?.protocol ?? null),
      messages: [
        ...(current?.status === 'HANDED_OFF' ? [] : (current?.messages ?? [])),
        { id: -Date.now(), role: 'USER', text: clean, source: null, createdAt: new Date().toISOString() },
      ],
    }))
    try {
      setConversation(await sendHelpMessage(clean))
    } catch (failure) {
      setError(messageOf(failure))
      setText(clean)
    } finally {
      setWaiting(false)
    }
  }

  async function handoff(event: FormEvent) {
    event.preventDefault()
    setError('')
    const digits = phone.replace(/\D/g, '').replace(/^55(?=\d{10,11}$)/, '')
    if (!/^[1-9]\d{9,10}$/.test(digits)) {
      setError('Informe o DDD e o número, como (11) 98765-4321.')
      return
    }
    try {
      setConversation(await askForPerson(digits))
      setAsking(false)
    } catch (failure) {
      setError(messageOf(failure))
    }
  }

  const messages = conversation?.messages ?? []
  const handedOff = conversation?.status === 'HANDED_OFF'
  const canAskPerson = conversation?.status === 'OPEN' && messages.some((m) => m.role === 'USER')

  return (
    <section className="help" aria-labelledby="help-title">
      <header className="help-head">
        <Lino size={72} level={waiting ? 0.9 : 0.4} />
        <div>
          <h1 id="help-title">Fale com o Lino</h1>
          <p className="muted">Tira dúvidas na hora. Se precisar, chama uma pessoa no WhatsApp.</p>
        </div>
      </header>

      {handedOff && conversation?.protocol && (
        <p className="protocol" role="status">
          Protocolo <strong>{conversation.protocol}</strong>. Uma pessoa vai falar com você no WhatsApp.
        </p>
      )}

      <div className="chat" aria-live="polite">
        {messages.length === 0 && !waiting && (
          <div className="chat-empty">
            <p>Oi! Sou o Lino. Pergunte o que quiser sobre a sua conta.</p>
            <div className="chips">
              {SUGGESTIONS.map((suggestion) => (
                <button key={suggestion} type="button" className="chip" onClick={() => void send(suggestion)}>
                  {suggestion}
                </button>
              ))}
            </div>
          </div>
        )}
        {messages.map((message) => (
          <div key={message.id} className="bubble" data-role={message.role}>
            <p>{message.text}</p>
            <small>
              {message.role === 'ASSISTANT' && (message.source === 'CLAUDE' ? 'Lino com IA · ' : 'Resposta pronta · ')}
              {hour.format(new Date(message.createdAt))}
            </small>
          </div>
        ))}
        {waiting && (
          <div className="bubble typing" data-role="ASSISTANT" aria-label="O Lino está escrevendo">
            <span />
            <span />
            <span />
          </div>
        )}
        <div ref={end} />
      </div>

      {error && <Alert>{error}</Alert>}

      {asking ? (
        <form className="handoff" onSubmit={(event) => void handoff(event)}>
          <label>
            Seu WhatsApp com DDD
            <input
              inputMode="tel"
              autoComplete="tel-national"
              placeholder="(11) 98765-4321"
              value={phone}
              onChange={(event) => setPhone(event.target.value)}
              required
            />
          </label>
          <p className="muted">A pessoa recebe o histórico desta conversa. Nunca pedimos senha pelo WhatsApp.</p>
          <div className="handoff-actions">
            <button type="button" className="button quiet" onClick={() => setAsking(false)}>
              Voltar
            </button>
            <button type="submit" className="button">
              Chamar atendente
            </button>
          </div>
        </form>
      ) : (
        <form
          className="composer"
          onSubmit={(event) => {
            event.preventDefault()
            void send(text)
          }}
        >
          <input
            aria-label="Sua mensagem"
            placeholder={handedOff ? 'Começar uma nova conversa' : 'Escreva sua dúvida'}
            maxLength={1000}
            value={text}
            onChange={(event) => setText(event.target.value)}
          />
          <button type="submit" className="button" disabled={waiting || !text.trim()}>
            Enviar
          </button>
        </form>
      )}

      {canAskPerson && !asking && (
        <button type="button" className="button quiet person" onClick={() => setAsking(true)}>
          Falar com atendente
        </button>
      )}
    </section>
  )
}
