import '@fontsource-variable/onest/wght.css'
import '@fontsource/michroma/latin-400.css'
import './styles.css'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router'
import { PrivacyProvider } from './lib/privacy'
import { SessionProvider } from './lib/session'
import { Login, Register } from './pages/Access'
import { AppShell } from './pages/AppShell'
import { Cards } from './pages/Cards'
import { Dashboard } from './pages/Dashboard'
import { Landing } from './pages/Landing'
import { Pix } from './pages/Pix'
import { Help } from './pages/Help'
import { Pay } from './pages/Pay'
import { Profile } from './pages/Profile'
import { Credit } from './pages/Credit'
import { Invest } from './pages/Invest'
import { Loan } from './pages/Loan'
import { Reviews } from './pages/Reviews'
import { Statement } from './pages/Statement'
import { Vaults } from './pages/Vaults'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <SessionProvider>
      <PrivacyProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/" element={<Landing />} />
            <Route path="/entrar" element={<Login />} />
            <Route path="/criar-conta" element={<Register />} />
            <Route path="/app" element={<AppShell />}>
              <Route index element={<Dashboard />} />
              <Route path="pix" element={<Pix />} />
              <Route path="cartoes" element={<Cards />} />
              <Route path="caixinhas" element={<Vaults />} />
              <Route path="investir" element={<Invest />} />
              <Route path="credito" element={<Credit />} />
              <Route path="emprestimo" element={<Loan />} />
              <Route path="analise" element={<Reviews />} />
              <Route path="extrato" element={<Statement />} />
              <Route path="ajuda" element={<Help />} />
              <Route path="pagar" element={<Pay />} />
              <Route path="conta" element={<Profile />} />
            </Route>
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </BrowserRouter>
      </PrivacyProvider>
    </SessionProvider>
  </StrictMode>,
)
