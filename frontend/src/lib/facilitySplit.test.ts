import { describe, expect, it } from 'vitest'
import { splitByOpenStatus } from './facilitySplit'
import type { Facility } from './types'

function facility(id: string, isOpenNow: boolean | null): Facility {
  return {
    id,
    type: 'pharmacy',
    nameKo: `${id} 약국`,
    nameEn: null,
    addressKo: null,
    addressEn: null,
    phone: null,
    latitude: 37.5,
    longitude: 127,
    distanceMeters: 100,
    operation: {
      isOpenNow,
      status: isOpenNow === true ? 'open' : isOpenNow === false ? 'closed' : 'unknown',
      statusConfidence: 'official_schedule',
      verifiedAt: null,
      scheduleUpdatedAt: null,
      notice: '',
    },
    source: {
      id: `source:${id}`,
      provider: 'test',
      recordId: id,
      retrievedAt: '2026-07-13T00:00:00Z',
      dataMode: 'fixture',
      title: 'Test fixture',
    },
  }
}

describe('splitByOpenStatus', () => {
  it('splits facilities into open, unknown, and confirmed-closed groups', () => {
    const open = facility('open', true)
    const unknown = facility('unknown', null)
    const closed = facility('closed', false)

    expect(splitByOpenStatus([closed, open, unknown])).toEqual({
      open: [open],
      unknown: [unknown],
      closed: [closed],
    })
  })

  it('returns three empty groups for empty input', () => {
    expect(splitByOpenStatus([])).toEqual({ open: [], unknown: [], closed: [] })
  })

  it('keeps all null-hours facilities in unknown and none in closed', () => {
    const first = facility('unknown-1', null)
    const second = facility('unknown-2', null)

    expect(splitByOpenStatus([first, second])).toEqual({
      open: [],
      unknown: [first, second],
      closed: [],
    })
  })

  it('never puts a confirmed-closed facility in unknown', () => {
    const closed = facility('closed', false)
    const split = splitByOpenStatus([closed])

    expect(split.unknown).toEqual([])
    expect(split.closed).toEqual([closed])
  })
})
