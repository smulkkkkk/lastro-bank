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
    for (let i = 0; i < 60; i++) {
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
    const cpf = digit(digit(String(Math.floor(Math.random() * 8e8) + 1e8)))
    const email = 'capturas-' + crypto.randomUUID() + '@teste.dev'
    const password = crypto.randomUUID()
    const post = async (path, body, headers = {}) => {
      const r = await fetch('/api/v1' + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) })
      if (!r.ok) throw new Error(path + ' ' + r.status)
      return r.json()
    }
    await post('/auth/register', { fullName: 'Cliente Capturas', email, cpf, password })
    const { accessToken } = await post('/auth/login', { email, password })
    const auth = { Authorization: 'Bearer ' + accessToken }
    const [account] = await (await fetch('/api/v1/accounts', { headers: auth })).json()
    for (const amountCents of [18000, 64050, 9990, 120000, 35000]) {
      await post('/accounts/' + account.id + '/deposits', { amountCents }, { ...auth, 'Idempotency-Key': crypto.randomUUID() })
    }
    localStorage.setItem('lastro-sessao', '1')
  })()`)

  const shots = {}
  const formats = { celular: [390, 844, true], tablet: [834, 1112, true], pc: [1440, 900, false] }
  for (const [format, [width, height, mobile]] of Object.entries(formats)) {
    await viewport(width, height, mobile)
    for (const [screen, path, ready] of [
      ['painel', '/app', '.ballast-path'],
      ['cartoes', '/app/cartoes', '.wallet'],
      ['extrato', '/app/extrato', '.entry'],
      ['conta', '/app/conta', '.next'],
    ]) {
      await open(path, ready)
      const name = `${format}-${screen}`
      await checkWidth(name)
      shots[name] = await shot()
      if (screen === 'painel' || screen === 'cartoes') await save(`${name}.png`, shots[name])
    }
    await open('/', '.wallet')
    await checkWidth(`${format}-inicial`)
  }

  // O menor celular que o projeto promete atender.
  await viewport(360, 640, true)
  for (const [path, ready] of [['/', '.wallet'], ['/app', '.ballast-path'], ['/app/cartoes', '.wallet'], ['/app/conta', '.next']]) {
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
    document.querySelector('.card-settings .switch').click(); await pause()
    const frozen = document.querySelectorAll('.card-frozen').length
    document.querySelector('[aria-label="Esconder valores"]').click(); await pause()
    return frozen
  })()`)
  if (new Set([behaviour.first, behaviour.second, behaviour.third]).size !== 3) problems.push('carteira não trocou: ' + JSON.stringify(behaviour))
  if (behaviour.frozen !== 1) problems.push('bloqueio não apareceu no cartão')
  await open('/app', '.ballast-path')
  const leaked = await run(`(document.querySelector('.app-main').innerText.match(/R\\$\\s?\\d/g) || []).length`)
  if (leaked) problems.push(`valores visíveis com o olho fechado: ${leaked}`)
  await run(`localStorage.removeItem('lastro-ocultar')`)

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
