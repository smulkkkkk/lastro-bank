import { Link } from 'react-router'
import { AccountCard } from '../components/AccountCard'
import { LedgerDemo } from '../components/LedgerDemo'
import { Brand, ThemeToggle } from '../components/ui'
import { useSession } from '../lib/session'

export function Landing() {
  const { status } = useSession()
  return (
    <div className="landing">
      <header className="topbar">
        <Brand />
        <nav aria-label="Principal">
          <ThemeToggle />
          {status === 'in' ? (
            <Link className="button" to="/app">
              Abrir minha conta
            </Link>
          ) : (
            <>
              <Link className="button quiet" to="/entrar">
                Entrar
              </Link>
              <Link className="button" to="/criar-conta">
                Criar conta
              </Link>
            </>
          )}
        </nav>
      </header>

      <main>
        <section className="hero">
          <div>
            <h1>Um banco em que cada centavo tem origem.</h1>
            <p className="lead">
              Abra uma conta em um minuto e receba R$ 1.000,00 para testar. Este é um projeto de demonstração: o
              dinheiro é fictício e nenhum dado real é necessário.
            </p>
            <div className="hero-actions">
              <Link className="button large" to={status === 'in' ? '/app' : '/criar-conta'}>
                {status === 'in' ? 'Abrir minha conta' : 'Criar conta'}
              </Link>
              <a className="button quiet large" href="#prova">
                Ver a regra funcionando
              </a>
            </div>
          </div>
          <div className="hero-card">
            <AccountCard branch="0001" number="100000-9" />
          </div>
        </section>

        <section className="proof" id="prova">
          <div>
            <h2>Tente criar dinheiro do nada</h2>
            <p>
              Todo movimento no Lastro tem dois lados: o que sai de uma conta entra em outra. Se os valores não batem,
              o próprio PostgreSQL desfaz a operação, mesmo que a aplicação esteja errada.
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
      </main>

      <footer className="footer">
        <span>Lastro não é um banco real. Projeto de portfólio em Java, PostgreSQL e React.</span>
        <a href="https://github.com/smulkkkkk/lastro-bank">Código no GitHub</a>
      </footer>
    </div>
  )
}
