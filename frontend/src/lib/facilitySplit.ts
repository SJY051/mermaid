import type { Facility } from './types'

/**
 * Splits facility results without collapsing unknown hours into closed.
 * Keeping null separate protects §2-3/spec §3 in both the Map tab and Chat's inline map.
 */
export interface OpenSplit {
  open: Facility[]
  unknown: Facility[]
  closed: Facility[]
}

export function splitByOpenStatus(facilities: Facility[]): OpenSplit {
  const split: OpenSplit = { open: [], unknown: [], closed: [] }

  for (const facility of facilities) {
    // NMC emergency-room records prove only that a facility exists at this location. Treat them as
    // hours-unknown even if an upstream regression accidentally attaches an open/closed flag.
    if (facility.type === 'emergency_room') {
      split.unknown.push(facility)
    } else if (facility.operation.isOpenNow === true) {
      split.open.push(facility)
    } else if (facility.operation.isOpenNow === false) {
      split.closed.push(facility)
    } else {
      split.unknown.push(facility)
    }
  }

  return split
}
