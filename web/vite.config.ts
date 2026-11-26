import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// O site e a API ficam na mesma origem, então o cookie de renovação continua SameSite=Strict.
// /live vai para o sentinel em Go: avisos ao vivo por Server-Sent Events.
const proxy = {
  '/api': { target: 'http://localhost:8080' },
  '/live': { target: 'http://127.0.0.1:8081', rewrite: (path: string) => path.replace(/^\/live/, '') },
}

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, strictPort: true, proxy },
  preview: { port: 5173, strictPort: true, proxy },
})
