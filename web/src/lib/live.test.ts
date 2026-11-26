import { describe, expect, it } from 'vitest'
import { parseSse } from './live'

describe('parseSse', () => {
  it('lê um aviso completo e ignora comentários', () => {
    const { events, rest } = parseSse('retry: 3000\n: conectado\n\nid: 7\nevent: notification\ndata: {"seq":7}\n\n')
    expect(events).toEqual([{ id: '7', event: 'notification', data: '{"seq":7}' }])
    expect(rest).toBe('')
  })

  it('guarda o pedaço incompleto para o próximo', () => {
    const first = parseSse('id: 1\nevent: notification\ndata: {"se')
    expect(first.events).toEqual([])
    const second = parseSse(first.rest + 'q":1}\n\n: ping\n\n')
    expect(second.events).toEqual([{ id: '1', event: 'notification', data: '{"seq":1}' }])
  })

  it('aceita CRLF e eventos sem dados com nome', () => {
    const { events } = parseSse('event: expired\r\ndata: {}\r\n\r\n')
    expect(events).toEqual([{ event: 'expired', data: '{}' }])
  })

  it('junta várias linhas de data', () => {
    expect(parseSse('data: a\ndata: b\n\n').events[0]?.data).toBe('a\nb')
  })
})
