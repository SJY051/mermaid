const DISCLAIMER = 'General information, not medical advice · Emergency? Call 119'

/**
 * Keeps the canonical safety message visible outside every screen's scrolling region.
 */
export function DisclaimerStrip() {
  return (
    <p className="border-t border-primary bg-surface px-3 py-[7px] text-center text-[11px] leading-tight text-primary">
      {/* An unreadable disclaimer is no disclaimer; Review guidelines treat this as P1. */}
      {DISCLAIMER}
    </p>
  )
}
