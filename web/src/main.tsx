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
import { Profile } from './pages/Profile'
import { Statement } from './pages/Statement'

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
              <Route path="cartoes" element={<Cards />} />
              <Route path="extrato" element={<Statement />} />
              <Route path="conta" element={<Profile />} />
            </Route>
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </BrowserRouter>
      </PrivacyProvider>
    </SessionProvider>
  </StrictMode>,
)
