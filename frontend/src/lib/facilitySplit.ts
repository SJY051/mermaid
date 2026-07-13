import type { Facility } from './types'

/**
 * Splits unfiltered facility results because backend `open_now=true` drops unknown hours.
 * Keeping null separate protects §2-3/spec §3; the Chat tab's inline map will adopt this later.
 */
export interface OpenSplit {
  open: Facility[]
  unknown: Facility[]
  closed: Facility[]
}

export function splitByOpenStatus(facilities: Facility[]): OpenSplit {
  const split: OpenSplit = { open: [], unknown: [], closed: [] }

  for (const facility of facilities) {
    if (facility.operation.isOpenNow === true) {
      split.open.push(facility)
    } else if (facility.operation.isOpenNow === false) {
      split.closed.push(facility)
    } else {
      split.unknown.push(facility)
    }
  }

  return split
}
