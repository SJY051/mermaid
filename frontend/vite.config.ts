import { defineConfig, loadEnv, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const ENV_DIR = '..'

/**
 * Anything named like a secret must never carry a `VITE_` prefix.
 *
 * Vite inlines every `VITE_*` variable into the JavaScript it serves. There is no runtime lookup
 * and no way to redact it afterwards: the literal string ends up in `dist/assets/index-*.js`, which
 * is public by definition. On 2026-07-10 the Naver **Client Secret** was pasted into
 * `VITE_NAVER_MAP_KEY_ID` and compiled into a bundle. We caught it, and the secret was rotated.
 *
 * Refusing to start is the point. A warning scrolls past; a build that will not run does not.
 */
function refuseSecretsInClientBundle(): Plugin {
  const LOOKS_SECRET = /SECRET|PASSWORD|PASSWD|PRIVATE_KEY|TOKEN|CREDENTIAL/i

  return {
    name: 'mermaid:refuse-secrets-in-client-bundle',
    config(_config, { mode }) {
      const offenders = Object.keys(loadEnv(mode, ENV_DIR, 'VITE_')).filter((k) => LOOKS_SECRET.test(k))
      if (offenders.length > 0) {
        throw new Error(
          `\n\n  ${offenders.join(', ')} — a VITE_ variable named like a secret.\n\n` +
            '  Vite inlines every VITE_ variable into the browser bundle, where anyone can read it.\n' +
            '  Move it to a name without the VITE_ prefix and read it from the server instead.\n' +
            '  See .env.example.\n',
        )
      }
    },
  }
}

export default defineConfig({
  plugins: [refuseSecretsInClientBundle(), react(), tailwindcss()],

  // One .env at the repo root feeds docker compose, Spring, and Vite alike.
  // Only VITE_-prefixed keys reach the browser, so the service keys stay server-side.
  envDir: ENV_DIR,

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
