// Point git at .githooks, from wherever this is called.
//
// The pre-commit secret guard only runs if `core.hooksPath` is set, and that setting lives in
// .git/config — which is per-clone and never committed. Anyone who cloned and went straight to
// `pnpm dev` had no guard at all. `pnpm install` runs this through the `prepare` script, so the
// guard installs itself.
//
// Node rather than a shell one-liner: this runs on macOS, Linux, and Windows (where pnpm hands
// scripts to cmd.exe, which does not speak `||` the way sh does).
//
// Failing here must never fail an install: a tarball or a vendored copy has no .git, and CI
// checkouts sometimes run scripts before git is configured. Warn, and let the install finish.

import { execFileSync } from 'node:child_process'

function git(...args) {
  return execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
}

try {
  git('rev-parse', '--git-dir')
} catch {
  process.exit(0) // Not a git checkout. Nothing to install, nothing to say.
}

try {
  const current = (() => {
    try {
      return git('config', '--local', 'core.hooksPath')
    } catch {
      return ''
    }
  })()

  if (current === '.githooks') process.exit(0)

  git('config', '--local', 'core.hooksPath', '.githooks')
  console.log('▸ pre-commit secret guard enabled (core.hooksPath = .githooks)')
} catch (error) {
  console.warn(`▸ could not enable the pre-commit secret guard: ${error.message}`)
  console.warn('▸ run `git config core.hooksPath .githooks` yourself — this repository is public')
}
