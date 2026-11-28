// Gera as imagens do README e os ícones, usando o app de verdade em um Edge ou Chrome invisível.
// Uso: com a API e o site no ar, `npm run capturas`. Sem dependências: fala direto com o navegador.
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const SITE = process.env.LASTRO_SITE ?? 'http://localhost:5173'
const PORT = 9337
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const images = resolve(root, '..', 'docs', 'images')
const wait = (ms) => new Promise((done) => setTimeout(done, ms))

const browser = [
  process.env.NAVEGADOR,
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  '/usr/bin/google-chrome',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
].find((path) => path && existsSync(path))
if (!browser) throw new Error('Defina NAVEGADOR com o caminho do Edge ou do Chrome')

const profile = await mkdtemp(join(tmpdir(), 'lastro-capturas-'))
const child = spawn(
  browser,
  ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, '--no-first-run', '--hide-scrollbars', '--enable-unsafe-swiftshader', 'about:blank'],
  { stdio: 'ignore' },
)

async function connect() {
  for (let i = 0; i < 60; i++) {
    try {
      const pages = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()
      const page = pages.find((item) => item.type === 'page')
      if (page) return new WebSocket(page.webSocketDebuggerUrl)
    } catch {
      // Ainda subindo.
    }
    await wait(250)
  }
  throw new Error('O navegador não abriu')
}

const problems = []

try {
  const socket = await connect()
  await new Promise((open, fail) => {
    socket.onopen = open
    socket.onerror = fail
  })
  let id = 0
  const pending = new Map()
  socket.onmessage = ({ data }) => {
    const message = JSON.parse(data)
    if (message.method === 'Runtime.exceptionThrown') {
      problems.push(message.params.exceptionDetails.exception?.description ?? 'exceção')
    }
    const waiting = pending.get(message.id)
    if (!waiting) return
    pending.delete(message.id)
    if (message.error) waiting.fail(new Error(message.error.message))
    else waiting.done(message.result)
  }
  const send = (method, params = {}) =>
    new Promise((done, fail) => {
      pending.set(++id, { done, fail })
      socket.send(JSON.stringify({ id, method, params }))
    })
  const run = async (expression) => {
    const { result, exceptionDetails } = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
    if (exceptionDetails) throw new Error(exceptionDetails.exception?.description ?? exceptionDetails.text)
    return result.value
  }
  let expected = 0
  const viewport = (width, height, mobile = false) => {
    expected = width
    return send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 2, mobile })
  }
  const open = async (path, ready) => {
    await send('Page.navigate', { url: SITE + path })
    // Até 30 s: em máquina com pouca memória a primeira carga demora.
    for (let i = 0; i < 150; i++) {
      await wait(200)
      if (await run(`location.pathname === ${JSON.stringify(path)} && !!document.querySelector(${JSON.stringify(ready)})`)) {
        await run('document.fonts.ready.then(() => true)')
        await wait(900)
        return
      }
    }
    throw new Error(`${path} não ficou pronta (${ready}); parou em ${await run('location.pathname')}`)
  }
  const shot = async () => Buffer.from((await send('Page.captureScreenshot', { format: 'png' })).data, 'base64')
  const save = async (name, buffer) => {
    await writeFile(join(images, name), buffer)
    console.log('ok', name)
  }
  // Serve de teste: nenhuma tela pode rolar para o lado em nenhum formato.
  const checkWidth = async (name) => {
    // No celular, innerWidth cresce junto com o que vaza; por isso compara com a largura pedida.
    const extra = (await run('Math.max(document.documentElement.scrollWidth, innerWidth)')) - expected
    if (extra > 0) problems.push(`${name}: sobra ${extra}px na largura`)
  }

  await mkdir(images, { recursive: true })
  await send('Page.enable')
  await send('Runtime.enable')
  await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] })

  for (const size of [192, 512]) {
    await send('Emulation.setDeviceMetricsOverride', { width: size, height: size, deviceScaleFactor: 1, mobile: false })
    await send('Page.navigate', { url: `${SITE}/icone.svg` })
    await wait(600)
    await writeFile(join(root, 'public', `icone-${size}.png`), await shot())
    console.log('ok', `icone-${size}.png`)
  }

  await viewport(1440, 900)
  await open('/', '.wallet')
  await checkWidth('pc-inicial')
  await save('inicial.png', await shot())

  // Conta nova e fictícia a cada execução.
  await run(`(async () => {
    const digit = (d) => { const s = [...d].reduce((t, c, i) => t + c * (d.length + 1 - i), 0) * 10 % 11; return d + (s === 10 ? 0 : s) }
    const post = async (path, body, headers = {}) => {
      const r = await fetch('/api/v1' + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) })
      if (!r.ok) throw new Error(path + ' ' + r.status)
      return r.json()
    }
    const enter = async (fullName) => {
      const cpf = digit(digit(String(Math.floor(Math.random() * 8e8) + 1e8)))
      const email = 'cliente-' + crypto.randomUUID().slice(0, 8) + '@teste.dev'
      const password = crypto.randomUUID()
      await post('/auth/register', { fullName, email, cpf, password })
      const { accessToken } = await post('/auth/login', { email, password })
      return { Authorization: 'Bearer ' + accessToken }
    }
    // Quem recebe entra primeiro: o cookie que fica é o de quem envia.
    const friend = await enter('Marina Duarte')
    const key = await post('/pix/keys', { kind: 'RANDOM' }, friend)
    const auth = await enter('Cliente Capturas')
    const [account] = await (await fetch('/api/v1/accounts', { headers: auth })).json()
    for (const amountCents of [18000, 64050, 9990, 120000, 35000]) {
      await post('/accounts/' + account.id + '/deposits', { amountCents }, { ...auth, 'Idempotency-Key': crypto.randomUUID() })
    }
    // Cabe no limite noturno, para as capturas saírem a qualquer hora.
    // Abaixo de R$ 800: conta recém-criada mandando mais que isso vai para revisão do antifraude.
    for (const [amountCents, message] of [[64000, 'aluguel'], [79000, 'viagem']]) {
      await post('/pix/transfers', { key: key.value, amountCents, message }, { ...auth, 'Idempotency-Key': crypto.randomUUID() })
    }
    // Espera o antifraude decidir, antes das caixinhas: o Rastro segue a ordem em que o dinheiro saiu.
    for (let i = 0; i < 80; i++) {
      const page = await (await fetch('/api/v1/accounts/' + account.id + '/statement', { headers: auth })).json()
      if (page.items.filter((e) => e.kind === 'TRANSFER').length === 2) break
      await new Promise((r) => setTimeout(r, 500))
    }
    const mine = await post('/pix/keys', { kind: 'EMAIL' }, auth)
    // Para a captura do aviso ao vivo: a Marina manda um Pix com a tela aberta. Some no fim.
    localStorage.setItem('capturas-amigo', JSON.stringify({ friend, key: mine.value }))
    await post('/help/messages', { text: 'Meu Pix ficou em revisão, o que acontece agora?' }, auth)
    const later = new Date(Date.now() + 200 * 864e5).toISOString().slice(0, 10)
    for (const [name, goalCents, lockedUntil, amountCents] of [['Viagem', 80000, null, 52000], ['Reserva', 300000, later, 40000], ['Teclado novo', 45000, null, 9000]]) {
      const vault = await post('/vaults', { name, goalCents, lockedUntil }, auth)
      await post('/vaults/' + vault.id + '/deposits', { amountCents }, { ...auth, 'Idempotency-Key': crypto.randomUUID() })
    }
    const cards = await (await fetch('/api/v1/cards', { headers: auth })).json()
    for (const [merchant, amountCents, mode, installments] of [['ANCORA', 129900, 'CREDIT', 6], ['FAROL', 8990, 'CREDIT', 1], ['ABISSAL', 4600, 'CREDIT', 1], ['MARE_CHEIA', 1850, 'DEBIT', 1]]) {
      await post('/cards/' + cards[0].id + '/purchases', { merchant, amountCents, mode, installments, contactless: mode === 'DEBIT' }, { ...auth, 'Idempotency-Key': crypto.randomUUID() })
    }
    // Um banco conectado pelo Open Finance, já autorizado.
    const consent = await post('/open-finance/consents', { bank: 'CORAL', permissions: ['BALANCES', 'TRANSACTIONS', 'PAYMENTS'], months: 12 }, auth)
    await post('/open-finance/consents/' + consent.id + '/authorization', { approve: true }, auth)
    // Um cupom guardado, para a tela de bolhas mostrar como fica.
    await post('/rewards/stores/ABISSAL/coupon', {}, auth)
    const quote = await (await fetch('/api/v1/loans/quote?amountCents=150000&installments=12', { headers: auth })).json()
    await post('/loans', { amountCents: 150000, installments: 12, acceptedTotalCents: quote.totalCents }, { ...auth, 'Idempotency-Key': crypto.randomUUID() })
    const market = await (await fetch('/api/v1/market', { headers: auth })).json()
    const price = (code) => market.assets.find((a) => a.code === code).ask
    for (const [asset, amountCents] of [['BTC', 25000], ['USD', 30000]]) {
      await post('/invest/buy', { asset, amountCents, expectedPrice: price(asset) }, { ...auth, 'Idempotency-Key': crypto.randomUUID() })
    }
    localStorage.setItem('lastro-sessao', '1')
  })()`)

  const shots = {}
  const formats = { celular: [390, 844, true], tablet: [834, 1112, true], pc: [1440, 900, false] }
  for (const [format, [width, height, mobile]] of Object.entries(formats)) {
    await viewport(width, height, mobile)
    for (const [screen, path, ready] of [
      ['painel', '/app', '.ballast-path'],
      ['pix', '/app/pix', '.pix-contacts'],
      ['caixinhas', '/app/caixinhas', '.tank'],
      ['investir', '/app/investir', '.assets'],
      ['credito', '/app/credito', '.invoice-items'],
      ['emprestimo', '/app/emprestimo', '.installments'],
      ['cartoes', '/app/cartoes', '.purchases li'],
      ['extrato', '/app/extrato', '.entry'],
      ['conta', '/app/conta', '.next'],
      ['ajuda', '/app/ajuda', '.bubble'],
      ['pagar', '/app/pagar', '.segmented'],
      ['bolhas', '/app/bolhas', '.store'],
      ['bancos', '/app/bancos', '.consent'],
    ]) {
      await open(path, ready)
      const name = `${format}-${screen}`
      await checkWidth(name)
      shots[name] = await shot()
      if (screen !== 'extrato' && screen !== 'conta') await save(`${name}.png`, shots[name])
    }
    await open('/app', '.entry-button')
    await run(`[...document.querySelectorAll('.entry-button')].find((e) => e.textContent.includes('790,00')).click()`)
    await wait(1800)
    await checkWidth(`${format}-rastro`)
    const origins = await run(`document.querySelectorAll('.trace-flow li').length`)
    if (origins !== 3) problems.push(`${format}: o Rastro devia ter 3 origens, tem ${origins}`)
    const spill = await run(`(() => { const d = document.querySelector('.trace'); return d.scrollWidth - d.clientWidth })()`)
    if (spill > 0) problems.push(`${format}: o Rastro vaza ${spill}px`)
    const touching = await run(`(() => { const r = [...document.querySelectorAll('.trace-flow li')].map((e) => e.getBoundingClientRect()); return r.some((a, i) => i > 0 && a.top < r[i - 1].bottom - 1) })()`)
    if (touching) problems.push(`${format}: textos do Rastro se sobrepõem`)
    await save(`${format}-rastro.png`, await shot())
    await open('/', '.wallet')
    await checkWidth(`${format}-inicial`)
  }

  // O menor celular que o projeto promete atender.
  await viewport(360, 640, true)
  for (const [path, ready] of [['/', '.wallet'], ['/app', '.ballast-path'], ['/app/pix', '.pix-contacts'], ['/app/caixinhas', '.tank'], ['/app/investir', '.assets'], ['/app/credito', '.invoice-items'], ['/app/emprestimo', '.installments'], ['/app/cartoes', '.wallet'], ['/app/conta', '.next'], ['/app/ajuda', '.bubble'], ['/app/pagar', '.segmented'], ['/app/bolhas', '.store'], ['/app/bancos', '.consent']]) {
    await open(path, ready)
    await checkWidth(`360px ${path}`)
  }

  // Comportamentos que uma imagem não mostra.
  await viewport(1280, 800)
  await open('/app/cartoes', '.wallet')
  const behaviour = await run(`(async () => {
    const label = () => document.querySelector('.wallet-controls p').textContent
    const pause = () => new Promise((r) => setTimeout(r, 900))
    const first = label()
    document.querySelector('[aria-label="Próximo cartão"]').click(); await pause()
    const second = label()
    const box = document.querySelector('.wallet-stage').getBoundingClientRect()
    return { first, second, x: box.left + box.width / 2, y: box.top + box.height / 2 }
  })()`)
  // Arrasto e toque com o mouse de verdade, não com eventos inventados.
  const mouse = async (type, x) => {
    await send('Input.dispatchMouseEvent', { type, x, y: behaviour.y, button: 'left', buttons: 1, clickCount: 1 })
    await wait(40)
  }
  await mouse('mousePressed', behaviour.x)
  for (let moved = 20; moved <= 260; moved += 20) await mouse('mouseMoved', behaviour.x - moved)
  await mouse('mouseReleased', behaviour.x - 260)
  await wait(700)
  behaviour.third = await run(`document.querySelector('.wallet-controls p').textContent`)
  const flipped = `document.querySelectorAll('.bank-card[data-flipped="true"]').length`
  if (await run(flipped)) problems.push('arrastar virou o cartão')
  await mouse('mousePressed', behaviour.x)
  await mouse('mouseReleased', behaviour.x)
  await wait(700)
  if ((await run(flipped)) !== 1) problems.push('tocar não virou o cartão da frente')
  behaviour.frozen = await run(`(async () => {
    const pause = () => new Promise((r) => setTimeout(r, 900))
    document.querySelector('.card-settings .switch').click()
    // A resposta da API pode demorar em máquina lenta: espera até 6 s.
    for (let i = 0; i < 20 && !document.querySelector('.card-frozen'); i++) await new Promise((r) => setTimeout(r, 300))
    const frozen = document.querySelectorAll('.card-frozen').length
    document.querySelector('[aria-label="Esconder valores"]').click(); await pause()
    return frozen
  })()`)
  if (new Set([behaviour.first, behaviour.second, behaviour.third]).size !== 3) problems.push('carteira não trocou: ' + JSON.stringify(behaviour))
  if (behaviour.frozen !== 1) problems.push('bloqueio não apareceu no cartão')
  // Gráfico de um ativo aberto, com o histórico real.
  await open('/app/investir', '.asset-card')
  await run(`[...document.querySelectorAll('button.asset-card')].find((b) => b.textContent.includes('Bitcoin')).click()`)
  for (let i = 0; i < 40 && !(await run(`!!document.querySelector('.price-line')`)); i++) await wait(250)
  if (!(await run(`!!document.querySelector('.price-line')`))) problems.push('o gráfico do bitcoin não apareceu')
  await save('pc-ativo.png', await shot())

  // Pix pela tela, do contato até o comprovante. Dois cliques em enviar não mandam duas vezes.
  await run(`localStorage.removeItem('lastro-ocultar')`)
  await open('/app/pix', '.pix-contacts button')
  const pix = await run(`(async () => {
    const pause = (ms) => new Promise((r) => setTimeout(r, ms))
    const until = async (selector) => { for (let i = 0; i < 80; i++) { const e = document.querySelector(selector); if (e) return e; await pause(100) } throw new Error('faltou ' + selector) }
    const balance = () => document.querySelector('.pix-facts dd').textContent
    const before = balance()
    document.querySelector('.pix-contacts button').click()
    const input = await until('.money-input')
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, '1234')
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await pause(150)
    const button = document.querySelector('.pix-step .button')
    const label = button.textContent
    button.click(); button.click()
    await until('.pix-receipt')
    await pause(2000)
    return { before, after: balance(), label, shown: document.querySelector('.pix-receipt').innerText }
  })()`)
  const digits = (text) => Number(text.replace(/\D/g, ''))
  if (digits(pix.before) - digits(pix.after) !== 1234) problems.push('Pix: saldo mudou errado: ' + JSON.stringify(pix))
  if (!pix.shown.includes('Marina Duarte')) problems.push('Pix: comprovante sem quem recebeu')
  await save('pc-comprovante.png', await shot())
  // Guardar e resgatar pela tela devolve o saldo ao começo.
  await open('/app/caixinhas', '.tank')
  const saving = await run(`(async () => {
    const pause = (ms) => new Promise((r) => setTimeout(r, ms))
    const total = () => document.querySelector('.vaults .balance').textContent
    const move = async (label) => {
      [...document.querySelectorAll('.tank')].find((t) => t.textContent.includes('Viagem')).querySelectorAll('.button').forEach((b) => b.textContent === label && b.click())
      await pause(300)
      const input = document.querySelector('.money-input')
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, '777')
      input.dispatchEvent(new Event('input', { bubbles: true }))
      await pause(150)
      const send = document.querySelector('.dialog-actions .button:not(.quiet)'); send.click(); send.click()
      await pause(2500)
    }
    const before = total()
    await move('Guardar')
    const middle = total()
    await move('Resgatar')
    return { before, middle, after: total() }
  })()`)
  if (digits(saving.middle) - digits(saving.before) !== 777 || saving.after !== saving.before) problems.push('Caixinha: ' + JSON.stringify(saving))
  await run(`localStorage.setItem('lastro-ocultar', '1')`)
  await open('/app/caixinhas', '.tank')
  await run(`(() => { const open = [...document.querySelectorAll(".tank .button")].find((b) => b.textContent === "Guardar"); open.click() })()`)
  await wait(600)
  // Os controles da simulação mostram o que a pessoa escolheu, não o dinheiro dela.
  const vaultLeak = await run(`(() => { const c = document.querySelector('.vaults').cloneNode(true); c.querySelector('.simulator-controls').remove(); return c.textContent.match(/R\\$\\s?\\d[\\d.,]*/g) || [] })()`)
  if (vaultLeak.length) problems.push('caixinhas mostram valores com o olho fechado: ' + vaultLeak.join(' '))
  await open('/app', '.ballast-path')
  await run(`document.querySelector('.entry-button').click()`)
  await wait(1500)
  const leaked = await run(`(document.querySelector('.app').innerText.match(/R\\$\\s?\\d/g) || []).length`)
  if (leaked) problems.push(`valores visíveis com o olho fechado: ${leaked}`)
  await run(`localStorage.removeItem('lastro-ocultar')`)

  // Boleto de teste conferido, pronto para pagar.
  await viewport(1440, 900)
  await open('/app/pagar', '.segmented')
  await run(`[...document.querySelectorAll('.link-button')].find((b) => b.textContent.includes('boleto de teste')).click()`)
  let readout = false
  for (let i = 0; i < 30 && !readout; i++) {
    await wait(300)
    readout = await run(`!!document.querySelector('.boleto-readout')`)
  }
  if (!readout) problems.push('o boleto de teste não apareceu')
  else {
    await checkWidth('pc-boleto')
    await save('pc-boleto.png', await shot())
    await run(`document.querySelector('.boleto-readout .button').click()`)
    let paid = false
    for (let i = 0; i < 30 && !paid; i++) {
      await wait(300)
      paid = await run(`!!document.querySelector('.receipt-sheet')`)
    }
    if (!paid) problems.push('o comprovante do boleto não apareceu')
    else {
      await wait(700)
      await checkWidth('pc-comprovante-boleto')
      await save('pc-comprovante-boleto.png', await shot())
    }
  }

  // Aviso ao vivo: o Pix chega pelo sentinel e aparece sem recarregar a página.
  await viewport(1440, 900)
  await open('/app', '.ballast-path')
  await wait(1500)
  await run(`(async () => {
    const { friend, key } = JSON.parse(localStorage.getItem('capturas-amigo'))
    localStorage.removeItem('capturas-amigo')
    await fetch('/api/v1/pix/transfers', { method: 'POST', headers: { 'Content-Type': 'application/json', ...friend, 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ key, amountCents: 15000, message: 'pizza' }) })
  })()`)
  let toast = false
  for (let i = 0; i < 40 && !toast; i++) {
    await wait(500)
    toast = await run(`!!document.querySelector('.toast')`)
  }
  if (!toast) problems.push('o aviso do Pix recebido não apareceu ao vivo')
  else await save('pc-aviso.png', await shot())
  await run(`document.querySelector('.bell').click()`)
  await wait(500)
  await save('pc-avisos.png', await shot())

  // Capa e versão noturna.
  await viewport(1280, 640)
  await open('/', '.stage')
  if (await run(`document.querySelector('.betta').dataset.failed === 'true'`)) problems.push('o peixe não desenhou')
  await save('capa.png', await shot())
  await run(`localStorage.setItem('lastro-tema', 'dark')`)
  await viewport(1440, 900)
  await open('/', '.stage')
  await save('inicial-noite.png', await shot())
  await open('/app/cartoes', '.wallet')
  await save('pc-cartoes-noite.png', await shot())
  socket.close()
} finally {
  child.kill()
  await wait(800)
  await rm(profile, { recursive: true, force: true }).catch(() => {})
}

if (problems.length > 0) {
  console.error('Problemas:\n- ' + problems.join('\n- '))
  process.exit(1)
}
console.log('Nenhum problema nas telas.')
