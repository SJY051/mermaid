#!/usr/bin/env node

import { spawnSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const FRONTEND_DIR = fileURLToPath(new URL('..', import.meta.url))
const VITE_CLI = join(FRONTEND_DIR, 'node_modules', 'vite', 'bin', 'vite.js')
const SERVER_SECRET_NAMES = ['LLM_API_KEY', 'DATA_GO_KR_SERVICE_KEY', 'NAVER_MAP_CLIENT_SECRET']

function sanitizedEnvironment() {
  return Object.fromEntries(
    Object.entries(process.env).filter(
      ([name, value]) =>
        value !== undefined &&
        !name.startsWith('VITE_') &&
        name !== 'DEBUG' &&
        !SERVER_SECRET_NAMES.includes(name),
    ),
  )
}

function outputFiles(directory) {
  const files = []
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) files.push(...outputFiles(path))
    else if (entry.isFile()) files.push(path)
  }
  return files
}

function containsAny(text, values) {
  return values.some((value) => text.includes(value))
}

function filesContainAny(files, values) {
  const needles = values.map((value) => Buffer.from(value))
  return files.some((file) => {
    const contents = readFileSync(file)
    return needles.some((needle) => contents.includes(needle))
  })
}

function runBuild(tempRoot, name, clientEnvironment = {}, mode = 'production') {
  const serverSentinels = Object.fromEntries(
    SERVER_SECRET_NAMES.map((variable) => [variable, `mermaid-server-secret-${randomUUID()}`]),
  )
  const outputDirectory = join(tempRoot, name)
  const result = spawnSync(
    process.execPath,
    [VITE_CLI, 'build', '--mode', mode, '--outDir', outputDirectory, '--emptyOutDir'],
    {
      cwd: FRONTEND_DIR,
      encoding: 'utf8',
      env: {
        ...sanitizedEnvironment(),
        ...serverSentinels,
        ...clientEnvironment,
      },
      maxBuffer: 16 * 1024 * 1024,
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  )

  const capturedOutput = `${result.stdout ?? ''}${result.stderr ?? ''}`
  const injectedValues = [...Object.values(serverSentinels), ...Object.values(clientEnvironment)]

  return {
    capturedOutput,
    injectedValues,
    outputDirectory,
    result,
    serverSentinels: Object.values(serverSentinels),
  }
}

function main() {
  const tempRoot = mkdtempSync(join(tmpdir(), 'mermaid-client-env-boundary-'))
  const cases = []

  const record = (name, passed, detail) => {
    cases.push({ name, passed, detail })
  }

  try {
    const serverOnly = runBuild(tempRoot, 'server-only')
    const serverOnlyFiles = serverOnly.result.status === 0 ? outputFiles(serverOnly.outputDirectory) : []
    record(
      'server-only secrets stay out of the client build',
      serverOnly.result.status === 0 &&
        serverOnlyFiles.length > 0 &&
        !containsAny(serverOnly.capturedOutput, serverOnly.serverSentinels) &&
        !filesContainAny(serverOnlyFiles, serverOnly.serverSentinels),
      `${serverOnlyFiles.length} output file(s) scanned`,
    )

    const publicValue = `mermaid-public-client-id-${randomUUID()}`
    const approved = runBuild(tempRoot, 'approved-public', {
      VITE_NAVER_MAP_CLIENT_ID: publicValue,
    })
    const approvedFiles = approved.result.status === 0 ? outputFiles(approved.outputDirectory) : []
    record(
      'the reviewed public Naver client ID builds',
      approved.result.status === 0 &&
        approvedFiles.length > 0 &&
        filesContainAny(approvedFiles, [publicValue]) &&
        !containsAny(approved.capturedOutput, approved.serverSentinels) &&
        !filesContainAny(approvedFiles, approved.serverSentinels),
      `${approvedFiles.length} output file(s) scanned`,
    )

    for (const variable of [
      'VITE_FUTURE_PUBLIC_ID',
      'VITE_LLM_API_KEY',
      'VITE_DATA_GO_KR_SERVICE_KEY',
    ]) {
      const value = `mermaid-rejected-client-value-${randomUUID()}`
      const rejected = runBuild(tempRoot, variable.toLowerCase(), { [variable]: value })
      record(
        `${variable} is rejected without disclosing its value`,
        rejected.result.status !== 0 &&
          rejected.capturedOutput.includes(variable) &&
          !containsAny(rejected.capturedOutput, rejected.injectedValues),
        'config load must fail by variable name only',
      )
    }

    const testModeValue = `mermaid-rejected-client-value-${randomUUID()}`
    const testModeRejected = runBuild(
      tempRoot,
      'test-mode-rejected',
      { VITE_FUTURE_PUBLIC_ID: testModeValue },
      'test',
    )
    record(
      'unreviewed client names are also rejected outside production mode',
      testModeRejected.result.status !== 0 &&
        testModeRejected.capturedOutput.includes('VITE_FUTURE_PUBLIC_ID') &&
        !containsAny(testModeRejected.capturedOutput, testModeRejected.injectedValues),
      'the same config guard must run in test mode',
    )

    const unsortedVariables = [
      'VITE_LLM_API_KEY',
      'VITE_FUTURE_PUBLIC_ID',
      'VITE_DATA_GO_KR_SERVICE_KEY',
    ]
    const multipleRejected = runBuild(
      tempRoot,
      'multiple-rejected',
      Object.fromEntries(
        unsortedVariables.map((variable) => [variable, `mermaid-rejected-client-value-${randomUUID()}`]),
      ),
    )
    record(
      'multiple rejected names are reported in sorted order without values',
      multipleRejected.result.status !== 0 &&
        multipleRejected.capturedOutput.includes([...unsortedVariables].sort().join(', ')) &&
        !containsAny(multipleRejected.capturedOutput, multipleRejected.injectedValues),
      'config error must be deterministic and name-only',
    )
  } finally {
    rmSync(tempRoot, { recursive: true, force: true })
  }

  for (const testCase of cases) {
    console.log(`${testCase.passed ? 'PASS' : 'FAIL'}  ${testCase.name} (${testCase.detail})`)
  }

  const failed = cases.filter((testCase) => !testCase.passed).length
  console.log(`\nclient env boundary: ${cases.length - failed}/${cases.length} cases passed`)
  process.exitCode = failed === 0 ? 0 : 1
}

main()
