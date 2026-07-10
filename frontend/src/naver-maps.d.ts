/// <reference types="navermaps" />

/**
 * `@types/navermaps` declares the `naver.maps` namespace but not the globals the loaded
 * script reads and assigns.
 */
declare global {
  interface Window {
    /** Assigned by the SDK once it has finished loading. */
    naver?: typeof naver

    /**
     * The only signal that the key was rejected.
     *
     * `maps.js` is served to anybody. A deliberately wrong `ncpKeyId` still answers 200 with the
     * same 333KB of JavaScript, and `script.onload` fires exactly as it would for a good key —
     * verified by curl, which is why curl cannot check a Naver key. Authentication happens later,
     * inside the SDK, and on failure the SDK calls this global. Define it BEFORE the script runs,
     * or the failure is silent: `ready` turns true and the user gets a blank grey box.
     */
    navermap_authFailure?: () => void
  }
}

export {}
