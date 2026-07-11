import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { JSDOM } from 'jsdom'
import { afterEach } from 'vitest'

/**
 * Give `localStorage` back.
 *
 * Node 26 ships its own experimental `localStorage` global. Without `--localstorage-file` its
 * getter returns `undefined`, and because it is an own property of the global object it wins over
 * the one jsdom installs. `sessionStorage` is untouched, so the symptom is a lopsided environment
 * where half of `src/lib/storage.ts` works and the other half throws `Cannot read properties of
 * undefined`. (Node 22, which CI runs, has no such global and takes neither branch below.)
 *
 * Rather than hand-roll a fake, borrow a real jsdom `Storage` from a throwaway window: the tests
 * then exercise the same implementation `sessionStorage` uses, quota errors and all.
 *
 * Patch `globalThis`, not just `window`. Vitest's jsdom environment happens to make them the same
 * object today, but `storage.ts` writes `localStorage` unqualified — so the binding that must be
 * repaired is the one on the global object, whatever `window` turns out to be.
 *
 * Delete this block once Node stops defining the global, or once vitest shadows it. The guards make
 * that safe — they do nothing on a runtime that already has a working `localStorage`.
 */
if (!globalThis.localStorage || !window.localStorage) {
  const donor = new JSDOM('', { url: window.location.href }).window.localStorage
  const install = { value: donor, configurable: true, writable: true }

  if (!globalThis.localStorage) Object.defineProperty(globalThis, 'localStorage', install)
  if (!window.localStorage) Object.defineProperty(window, 'localStorage', install)
}

/**
 * jsdom implements no `matchMedia`, and astryx's theme hook calls it from `useSyncExternalStore`
 * (any component that renders a Spinner or reads the theme). A minimal, standards-shaped stub is
 * enough: never matches, supports both the modern and the legacy listener API.
 */
if (!window.matchMedia) {
  window.matchMedia = (query: string): MediaQueryList =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList
}

// React Testing Library leaves the last render mounted. Two component tests in one file would
// otherwise both match the same query, and the second would assert against the first one's DOM.
afterEach(cleanup)
