import { fileURLToPath } from 'node:url'
import { loadEnv, type Plugin } from 'vite'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const ENV_DIR = fileURLToPath(new URL('..', import.meta.url))
const REVIEWED_PUBLIC_CLIENT_ENV = new Set(['VITE_NAVER_MAP_CLIENT_ID'])

/**
 * Only reviewed public values may carry a `VITE_` prefix.
 *
 * Vite inlines every `VITE_*` variable into the JavaScript it serves. There is no runtime lookup
 * and no way to redact it afterwards: the literal string ends up in `dist/assets/index-*.js`, which
 * is public by definition. On 2026-07-10 the Naver **Client Secret** was pasted into
 * `VITE_NAVER_MAP_KEY_ID` and compiled into a bundle. We caught it, and the secret was rotated.
 *
 * Refusing to start is the point. A warning scrolls past; a build that will not run does not.
 */
function refuseUnreviewedClientEnvironment(): Plugin {
  return {
    name: 'mermaid:refuse-unreviewed-client-environment',
    config(_config, { mode }) {
      const offenders = Object.keys(loadEnv(mode, ENV_DIR, 'VITE_'))
        .filter((name) => !REVIEWED_PUBLIC_CLIENT_ENV.has(name))
        .sort()
      if (offenders.length > 0) {
        throw new Error(
          `\n\n  ${offenders.join(', ')} — an unreviewed VITE_ variable.\n\n` +
            '  Vite inlines every VITE_ variable into the browser bundle, where anyone can read it.\n' +
            '  Only VITE_NAVER_MAP_CLIENT_ID is approved as public browser configuration.\n' +
            '  Move server values to names without the VITE_ prefix.\n' +
            '  See .env.example.\n',
        )
      }
    },
  }
}

export default defineConfig({
  plugins: [refuseUnreviewedClientEnvironment(), react(), tailwindcss()],

  // Tests run through this same config on purpose: the secret guard above fires during
  // `pnpm test` too, so a badly-named key fails the suite as well as the build.
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    restoreMocks: true,
  },

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
