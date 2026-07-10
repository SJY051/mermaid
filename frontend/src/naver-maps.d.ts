/// <reference types="navermaps" />

/**
 * `@types/navermaps` declares the `naver.maps` namespace but not the global that the
 * loaded script assigns. We check `window.naver` to know whether the SDK has finished
 * loading, so it needs a type.
 */
declare global {
  interface Window {
    naver?: typeof naver
  }
}

export {}
