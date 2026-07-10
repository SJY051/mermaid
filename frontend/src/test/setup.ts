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
 * undefined`.
 *
 * Rather than hand-roll a fake, borrow a real jsdom `Storage` from a throwaway window: the tests
 * then exercise the same implementation `sessionStorage` uses, quota errors and all.
 *
 * Delete this block once Node stops defining the global, or once vitest shadows it. The guard
 * makes that safe — it does nothing on a runtime that already has a working `localStorage`.
 */
if (!window.localStorage) {
  const donor = new JSDOM('', { url: window.location.href }).window.localStorage
  Object.defineProperty(window, 'localStorage', { value: donor, configurable: true })
}

// React Testing Library leaves the last render mounted. Two component tests in one file would
// otherwise both match the same query, and the second would assert against the first one's DOM.
afterEach(cleanup)
