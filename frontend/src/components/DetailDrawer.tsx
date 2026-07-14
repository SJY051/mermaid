import { useEffect, useId, useRef } from 'react'
import type { Facility } from '../lib/types'

export interface DetailDrawerProps {
  facility: Facility
  onClose: () => void
}

function facilityTypeLabel(facility: Facility): string {
  switch (facility.type) {
    case 'pharmacy':
      return 'Pharmacy'
    case 'hospital':
      return 'Hospital'
    case 'emergency_room':
      return 'Emergency room'
  }
}

function operationLabel(facility: Facility): string {
  if (facility.operation.isOpenNow === true) return 'Open now'
  if (facility.operation.isOpenNow === false) return 'Closed'
  return 'Hours unknown'
}

function operationGlyph(facility: Facility): string {
  if (facility.operation.isOpenNow === true) return '✓'
  if (facility.operation.isOpenNow === false) return '×'
  return '?'
}

function operationGlyphClass(facility: Facility): string {
  if (facility.operation.isOpenNow === true) return 'bg-[#1a7a34] text-white'
  if (facility.operation.isOpenNow === false) return 'bg-[#9aa0a8] text-[#1a1a1a]'
  return 'bg-[#e0a800] text-[#1a1a1a]'
}

/**
 * The facility detail bottom sheet (UI-03, DEV-207).
 *
 * The first implementation will use the `Facility` already returned by the nearby-facilities
 * list. Live detail refetching belongs to a later step, after the single-facility endpoint exists.
 */
export function DetailDrawer({ facility, onClose }: DetailDrawerProps) {
  const titleId = useId()
  const closeButtonRef = useRef<HTMLButtonElement>(null)
  const address = facility.addressEn ?? facility.addressKo

  useEffect(() => {
    closeButtonRef.current?.focus()

    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [onClose])

  return (
    <div className="fixed inset-0 z-50 flex items-end bg-black/45" data-testid="detail-drawer-backdrop">
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="max-h-[85vh] w-full overflow-y-auto rounded-t-2xl border border-primary bg-primary p-5 shadow-2xl"
      >
        <div className="mx-auto mb-4 h-1 w-12 rounded-full bg-tertiary" aria-hidden="true" />

        <header className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <h2 id={titleId} className="text-xl font-semibold text-primary" lang="ko">
              {facility.nameKo}
            </h2>
            {facility.nameEn && <p className="mt-1 text-sm text-secondary">{facility.nameEn}</p>}
          </div>
          <button
            ref={closeButtonRef}
            type="button"
            aria-label="Close facility details"
            className="grid h-10 w-10 shrink-0 place-items-center rounded-full border border-primary text-xl text-primary focus-visible:outline-2 focus-visible:outline-offset-2"
            onClick={onClose}
          >
            <span aria-hidden="true">×</span>
          </button>
        </header>

        <div className="mt-5 space-y-4 text-sm">
          <div className="flex flex-wrap items-center gap-3">
            <span className="rounded-full border border-primary px-3 py-1 text-primary">
              {facilityTypeLabel(facility)}
            </span>
            <span className="flex items-center gap-2 font-medium text-primary">
              <span
                data-testid="detail-operation-glyph"
                className={`grid h-5 w-5 place-items-center rounded-full text-xs font-bold ${operationGlyphClass(facility)}`}
                aria-hidden="true"
              >
                {operationGlyph(facility)}
              </span>
              {operationLabel(facility)}
            </span>
            <span className="text-secondary">{Math.round(facility.distanceMeters)} m away</span>
          </div>

          <div>
            <p className="text-xs font-medium uppercase tracking-wide text-secondary">Address</p>
            <p
              className="mt-1 text-primary"
              lang={address ? (facility.addressEn ? 'en' : 'ko') : 'en'}
            >
              {address ?? 'Address unavailable'}
            </p>
          </div>

          {facility.phone && (
            <a
              href={`tel:${facility.phone}`}
              aria-label={`Call ${facility.phone}`}
              className="flex min-h-11 items-center justify-center rounded-lg border border-primary px-4 py-2 font-medium text-primary"
            >
              Call {facility.phone}
            </a>
          )}

          {facility.operation.notice && (
            <p className="rounded-lg bg-secondary p-3 text-primary">{facility.operation.notice}</p>
          )}

          <div className="border-t border-primary pt-4">
            <p data-testid="facility-source" className="text-xs text-primary">
              <span aria-hidden="true">✓ </span>
              <span>{facility.source.title}</span>
              {' · '}
              {facility.source.retrievedAt.slice(0, 10)}
            </p>
            {facility.source.dataMode === 'fixture' && (
              <p className="mt-2 text-xs font-medium text-primary">Sample data</p>
            )}
          </div>
        </div>
      </section>
    </div>
  )
}
