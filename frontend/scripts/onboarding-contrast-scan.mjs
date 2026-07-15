#!/usr/bin/env node
/**
 * Reads every rendered onboarding text node's contrast in BOTH themes and fails on any WCAG AA
 * miss. The three screens are reached as a first-time visitor, through their visible Next buttons.
 *
 * Usage:  node scripts/onboarding-contrast-scan.mjs [url]   (default http://localhost:5173)
 * Needs the dev server running. Exit 0 means all three screens passed in light AND dark.
 */
import { chromium } from 'playwright'

const URL = process.argv[2] ?? 'http://localhost:5173'
const ONBOARDING_KEY = 'mermaid.onboarding.v1'

/** WCAG 2.1 AA thresholds are applied in-page: 4.5:1, or 3:1 for large text (≥24px, ≥18.66px bold). */
const IN_PAGE = () => {
  const srgb = (c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)
  const parse = (s) => {
    const m = s.match(/rgba?\(([^)]+)\)/)
    if (!m) return null
    const [r, g, b, a = '1'] = m[1].split(/[,\s/]+/).filter(Boolean)
    return { r: +r, g: +g, b: +b, a: +a }
  }
  const lum = ({ r, g, b }) =>
    0.2126 * srgb(r / 255) + 0.7152 * srgb(g / 255) + 0.0722 * srgb(b / 255)
  const over = (fg, bg) => ({
    r: fg.r * fg.a + bg.r * (1 - fg.a),
    g: fg.g * fg.a + bg.g * (1 - fg.a),
    b: fg.b * fg.a + bg.b * (1 - fg.a),
    a: 1,
  })
  const opacity = (el) => {
    let value = 1
    for (let n = el; n; n = n.parentElement) value *= +getComputedStyle(n).opacity
    return value
  }

  // Match the app-wide contrast scan: resolve transparent layers against the first painted
  // ancestor background, then against white if the entire tree is transparent.
  const backdrop = (el) => {
    let acc = null
    for (let n = el; n; n = n.parentElement) {
      const bg = parse(getComputedStyle(n).backgroundColor)
      if (!bg || bg.a === 0) continue
      acc = acc ? over(acc, bg) : bg
      if (acc.a >= 1) return acc
    }
    return over(acc ?? { r: 0, g: 0, b: 0, a: 0 }, { r: 255, g: 255, b: 255, a: 1 })
  }

  const results = []
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT)
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const text = node.textContent.trim()
    if (!text) continue
    const el = node.parentElement
    if (!el) continue
    const cs = getComputedStyle(el)
    if (cs.visibility === 'hidden' || cs.display === 'none' || +cs.opacity === 0) continue
    const box = el.getBoundingClientRect()
    if (box.width === 0 || box.height === 0) continue

    // Screen-reader-only text is clipped rather than painted and therefore has no visual contrast.
    const clipped =
      cs.clipPath !== 'none' ||
      (cs.clip !== 'auto' && cs.clip !== '') ||
      ((box.width <= 1 || box.height <= 1) && cs.overflow === 'hidden')
    if (clipped) continue

    const fg = parse(cs.color)
    if (!fg) continue
    fg.a *= opacity(el)
    const bg = backdrop(el)
    const flat = over(fg, bg)
    const [l1, l2] = [lum(flat), lum(bg)].sort((a, b) => b - a)
    const ratio = (l1 + 0.05) / (l2 + 0.05)

    const size = parseFloat(cs.fontSize)
    const bold = +cs.fontWeight >= 700
    const large = size >= 24 || (bold && size >= 18.66)

    results.push({
      text: text.slice(0, 60),
      ratio: Math.round(ratio * 100) / 100,
      required: large ? 3.0 : 4.5,
      color: cs.color,
      background: `rgb(${Math.round(bg.r)}, ${Math.round(bg.g)}, ${Math.round(bg.b)})`,
      selector: el.tagName.toLowerCase() + (el.className ? '.' + String(el.className).split(/\s+/)[0] : ''),
    })
  }
  return results
}

const SCREENS = [
  'Onboarding screen 1 of 3',
  'Onboarding screen 2 of 3',
  'Onboarding screen 3 of 3',
]

async function firstVisible(locator) {
  for (let index = 0; index < (await locator.count()); index += 1) {
    const candidate = locator.nth(index)
    if (await candidate.isVisible()) return candidate
  }
  return null
}

const browser = await chromium.launch()
let failures = 0
let scanned = 0

try {
  for (const scheme of ['light', 'dark']) {
    const page = await browser.newPage({ colorScheme: scheme, viewport: { width: 390, height: 844 } })

    // Remove the seen flag before React's first render. Clearing it after navigation is too late:
    // MobileShell reads the value only once while initializing state.
    await page.addInitScript((key) => localStorage.removeItem(key), ONBOARDING_KEY)
    await page.goto(URL, { waitUntil: 'networkidle' })

    for (let index = 0; index < SCREENS.length; index += 1) {
      const label = SCREENS[index]
      const screen = await firstVisible(page.getByRole('main', { name: label, exact: true }))

      if (!screen) {
        console.log(
          `FAIL  ${scheme.padEnd(5)} ${label.padEnd(24)} screen not found — the scan never reached it`,
        )
        failures += 1
        break
      }

      const found = (await page.evaluate(IN_PAGE)).filter((result) => result.ratio < result.required)
      scanned += 1

      if (found.length === 0) {
        console.log(`PASS  ${scheme.padEnd(5)} ${label.padEnd(24)} 0 failures`)
      }

      for (const failure of found) {
        failures += 1
        console.log(
          `FAIL  ${scheme.padEnd(5)} ${label.padEnd(24)} ${String(failure.ratio).padStart(5)}:1 ` +
            `(needs ${failure.required})  ${failure.color} on ${failure.background}  ${failure.selector}\n` +
            `        “${failure.text}”`,
        )
      }

      if (index === SCREENS.length - 1) continue

      const next = await firstVisible(screen.getByRole('button', { name: 'Next', exact: true }))
      if (!next) {
        const nextLabel = SCREENS[index + 1]
        console.log(
          `FAIL  ${scheme.padEnd(5)} ${nextLabel.padEnd(24)} visible Next control not found on ${label}`,
        )
        failures += 1
        break
      }

      await next.click()
      await page.waitForTimeout(600)
    }

    await page.close()
  }
} finally {
  await browser.close()
}

console.log(
  failures === 0
    ? `\n✅ onboarding contrast: 0 failures across ${scanned} screens (light + dark)`
    : `\n❌ onboarding contrast: ${failures} failures across ${scanned} screens`,
)
process.exitCode = failures === 0 ? 0 : 1
