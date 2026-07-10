#!/usr/bin/env bash
#
# One-time setup for a fresh clone. Safe to re-run.
#
#   ./bin/setup.sh

set -euo pipefail
cd "$(dirname "$0")/.."

echo "▸ Enabling the pre-commit secret guard"
# `pnpm install` below sets this too, via bin/install-hooks.mjs, for anyone who never runs
# this file. Done here as well so the guard is on before we touch anything else — and with
# plain git, because node may not be installed yet.
git config core.hooksPath .githooks
chmod +x .githooks/pre-commit

if [ ! -f .env ]; then
    echo "▸ Creating .env from .env.example — fill in your keys before running the backend"
    cp .env.example .env
else
    echo "▸ .env already exists, leaving it alone"
fi

echo "▸ Installing frontend dependencies"
(cd frontend && pnpm install)

echo "▸ Warming the Gradle wrapper"
(cd backend && ./gradlew --version >/dev/null)

cat <<'EOF'

  Setup complete. Now:

    1. Fill in .env
         DATA_GO_KR_SERVICE_KEY   → data.go.kr, use the DECODING key
         VITE_NAVER_MAP_CLIENT_ID    → NCP console > Maps (register http://localhost)
         LLM_API_KEY              → your OpenAI-compatible endpoint

    2. Start the infrastructure
         docker compose up -d

    3. Run the backend            (http://localhost:8080)
         cd backend && ./gradlew bootRun

    4. Run the frontend           (http://localhost:5173)
         cd frontend && pnpm dev

  Read docs/specs/001-foundation/spec.md §3 before you touch a public API.
  It lists the traps that will otherwise cost you an afternoon each.

EOF
