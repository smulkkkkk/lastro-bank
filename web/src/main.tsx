import '@fontsource-variable/onest/wght.css'
import './styles.css'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router'
import { SessionProvider } from './lib/session'
import { Login, Register } from './pages/Access'
import { AppShell } from './pages/AppShell'
import { Dashboard } from './pages/Dashboard'
import { Landing } from './pages/Landing'
import { Statement } from './pages/Statement'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <SessionProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<Landing />} />
          <Route path="/entrar" element={<Login />} />
          <Route path="/criar-conta" element={<Register />} />
          <Route path="/app" element={<AppShell />}>
            <Route index element={<Dashboard />} />
            <Route path="extrato" element={<Statement />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </SessionProvider>
  </StrictMode>,
)
