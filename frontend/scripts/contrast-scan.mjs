#!/usr/bin/env node
/**
 * Reads every rendered text node's contrast against what is actually behind it, in BOTH themes,
 * and fails the run on any WCAG AA miss.
 *
 * Why this exists rather than a design review: contrast is machine-decidable, so eyeballing it is a
 * non-deterministic sample of a thing a computer can census. On greyscale 3.25:1 and 4.5:1 look
 * identical, and a colour that only fails in dark is invisible on a light frame. Six eyeball rounds
 * over this app's wireframe missed what one pass of this found at once — 21 failures, including a
 * grey that failed only in dark (2026-07-12).
 *
 * Usage:  pnpm --dir frontend contrast [url]        (default http://localhost:5173)
 * Needs the dev server running. Exit 0 means zero failures in light AND dark.
 */
import { chromium } from 'playwright'

const URL = process.argv[2] ?? 'http://localhost:5173'

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

  // The background a pixel actually has is the first non-transparent one up the tree — a `bg-red`
  // parent behind a transparent child is what the eye sees, and comparing against the child's own
  // `rgba(0,0,0,0)` would score every nested element as pure black and pass everything.
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

    // Screen-reader-only text has no colour problem, because it has no colour: it is clipped to a
    // pixel and never painted. Counting it drowns the real failures — the map's own aria labels
    // alone are dozens of them — and a drowned signal is a scan nobody runs. Detected by the shape
    // every visually-hidden helper has, not by a class name we would have to keep in sync.
    const clipped =
      cs.clipPath !== 'none' ||
      (cs.clip !== 'auto' && cs.clip !== '') ||
      ((box.width <= 1 || box.height <= 1) && cs.overflow === 'hidden')
    if (clipped) continue

    const fg = parse(cs.color)
    if (!fg) continue
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

/** Every tab, so the scan sees the whole app and not just the one screen that happened to load. */
const TABS = ['Chat', 'Map', 'Saved', 'Settings']

const browser = await chromium.launch()
let failures = 0
let scanned = 0

for (const scheme of ['light', 'dark']) {
  const page = await browser.newPage({ colorScheme: scheme, viewport: { width: 390, height: 844 } })
  await page.goto(URL, { waitUntil: 'networkidle' })

  for (const tab of TABS) {
    const button = page.getByRole('button', { name: tab, exact: true })
    if (await button.count()) {
      await button.first().click()
      await page.waitForTimeout(600)
    }
    const found = (await page.evaluate(IN_PAGE)).filter((r) => r.ratio < r.required)
    scanned += 1
    for (const f of found) {
      failures += 1
      console.log(
        `FAIL  ${scheme.padEnd(5)} ${tab.padEnd(9)} ${String(f.ratio).padStart(5)}:1 ` +
          `(needs ${f.required})  ${f.color} on ${f.background}  ${f.selector}\n` +
          `        “${f.text}”`,
      )
    }
  }
  await page.close()
}

await browser.close()

console.log(
  failures === 0
    ? `\n✅ contrast: 0 failures across ${scanned} screens (light + dark)`
    : `\n❌ contrast: ${failures} failures across ${scanned} screens`,
)
process.exit(failures === 0 ? 0 : 1)
