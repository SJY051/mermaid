import type { Facility } from '../lib/types'

export interface DetailDrawerProps {
  facility: Facility
  onClose: () => void
}

/**
 * The facility detail bottom sheet (UI-03, DEV-207).
 *
 * The first implementation will use the `Facility` already returned by the nearby-facilities
 * list. Live detail refetching belongs to a later step, after the single-facility endpoint exists.
 */
export function DetailDrawer({ facility, onClose }: DetailDrawerProps) {
  // TODO(DEV-207): assemble the accessible bottom sheet and render the facility facts.
  // Keep `isOpenNow: null` distinct from `false`: it means "Hours unknown", never "Closed".
  void facility
  void onClose
  return null
}
