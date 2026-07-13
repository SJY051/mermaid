const DISCLAIMER = 'General information, not medical advice · Emergency? Call 119'

/**
 * Keeps the canonical safety message visible outside every screen's scrolling region.
 */
export function DisclaimerStrip() {
  return (
    <p className="bg-surface px-3 py-2 text-center text-xs text-primary">
      {/* An unreadable disclaimer is no disclaimer; Review guidelines treat this as P1. */}
      {DISCLAIMER}
    </p>
  )
}
