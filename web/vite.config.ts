import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// O site e a API ficam na mesma origem, então o cookie de renovação continua SameSite=Strict.
const proxy = { '/api': { target: 'http://localhost:8080' } }

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, strictPort: true, proxy },
  preview: { port: 5173, strictPort: true, proxy },
})
