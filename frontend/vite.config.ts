import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],

  // One .env at the repo root feeds docker compose, Spring, and Vite alike.
  // Only VITE_-prefixed keys reach the browser, so the service keys stay server-side.
  envDir: '..',

  server: {
    proxy: {
      // Proxying keeps the browser on a single origin, which is not a nicety.
      // The `openai` SDK attaches Authorization plus a handful of x-stainless-*
      // headers; all of them are non-simple, so a cross-origin call triggers a
      // CORS preflight that Spring would have to allowlist header by header.
      // Same-origin means no preflight at all. See docs/specs/001-foundation/spec.md §2-3.
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
