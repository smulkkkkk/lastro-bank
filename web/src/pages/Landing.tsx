import { useState } from 'react'
import { Link } from 'react-router'
import { BettaCanvas } from '../components/BettaCanvas'
import { LedgerDemo } from '../components/LedgerDemo'
import { Lino } from '../components/Lino'
import { Wordmark } from '../components/Marks'
import { Brand, ThemeToggle } from '../components/ui'
import { Wallet } from '../components/Wallet'
import { useSession } from '../lib/session'

const SAMPLE = [
  { design: 'ONIX' as const, label: 'Principal', last4: '2611', frozen: false, contactless: true },
  { design: 'MARE' as const, label: 'Compras online', last4: '0708', frozen: false, contactless: true },
  { design: 'BRASA' as const, label: 'Assinaturas', last4: '1933', frozen: false, contactless: true },
]

export function Landing() {
  const { status } = useSession()
  const [position, setPosition] = useState(0)
  const inside = status === 'in'

  return (
    <div className="landing">
      <section className="stage">
        <BettaCanvas />
        <header className="stage-top">
          <div className="stage-left">
            {inside ? null : <Link to="/entrar">Entrar</Link>}
            <a href="#prova">Como funciona</a>
          </div>
          <Brand />
          <div className="stage-right">
            <ThemeToggle />
            <Link className="button" to={inside ? '/app' : '/criar-conta'}>
              {inside ? 'Abrir conta' : 'Criar conta'}
            </Link>
          </div>
        </header>

        <div className="stage-name">
          <Wordmark />
          <span>Banco digital</span>
        </div>

        <div className="stage-fact">
          <strong>Zero centavos</strong>
          <span>perdidos em 200 movimentos simultâneos, nos testes do projeto</span>
        </div>
        <a className="stage-cue" href="#carteira">
          Ver os cartões
        </a>
        <div className="stage-line">
          <h1>Cada centavo tem origem</h1>
          <p>Projeto de demonstração. O dinheiro é fictício.</p>
        </div>
      </section>

      <main>
        <section className="pocket" id="carteira">
          <h2>Três cartões em um só bolso</h2>
          <Wallet cards={SAMPLE} position={position} onChange={setPosition} />
        </section>

        <section className="proof" id="prova">
          <div>
            <h2>Tente criar dinheiro do nada</h2>
            <p>
              Todo movimento no Lastro tem dois lados: o que sai de uma conta entra em outra. Se os valores não batem, o
              próprio PostgreSQL desfaz a operação, mesmo que a aplicação esteja errada.
            </p>
          </div>
          <LedgerDemo />
        </section>

        <section className="refusals">
          <h2>O que este banco recusa</h2>
          <dl>
            <div>
              <dt>Gastar duas vezes</dt>
              <dd>As contas são travadas antes de mover o dinheiro. Vinte tentativas juntas não passam do saldo.</dd>
            </div>
            <div>
              <dt>Reescrever o passado</dt>
              <dd>Lançamentos não podem ser editados nem apagados. Corrigir é lançar um estorno.</dd>
            </div>
            <div>
              <dt>Repetir sem querer</dt>
              <dd>Clicar de novo ou perder a conexão não deposita outra vez. O pedido repetido é reconhecido.</dd>
            </div>
            <div>
              <dt>Sessão copiada</dt>
              <dd>Se alguém reutilizar uma sessão sua, ela é encerrada para todos os aparelhos que a usam.</dd>
            </div>
          </dl>
        </section>

        <section className="meet">
          <Lino size={200} />
          <div>
            <h2>Este é o Lino</h2>
            <p>
              Abra uma conta em um minuto e receba R$ 1.000,00 fictícios. O Lino vai inflar conforme você guarda
              dinheiro. Toque nele para ver.
            </p>
            <Link className="button large" to={inside ? '/app' : '/criar-conta'}>
              {inside ? 'Abrir conta' : 'Criar conta'}
            </Link>
          </div>
        </section>
      </main>

      <footer className="footer">
        <span>Lastro não é um banco real. Projeto de portfólio em Java, PostgreSQL e React.</span>
        <a href="https://github.com/smulkkkkk/lastro-bank">Código no GitHub</a>
      </footer>
    </div>
  )
}
