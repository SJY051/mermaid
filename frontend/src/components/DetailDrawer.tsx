import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { Check, Phone, X } from 'lucide-react'
import { useFavorites } from '../lib/favorites'
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
  if (facility.operation.isOpenNow === true) {
    return 'border border-green-ring bg-green-subtle text-green-vivid'
  }
  if (facility.operation.isOpenNow === false) {
    return 'border border-strong bg-muted text-primary'
  }
  return 'border border-yellow-ring bg-yellow-subtle text-yellow-vivid'
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
  const { favorites, savingFacilityId, saveFacility } = useFavorites()
  const [saveError, setSaveError] = useState<string | null>(null)
  const [closing, setClosing] = useState(false)
  const saved = favorites.some((favorite) => favorite.facilityId === facility.id)
  const saving = savingFacilityId === facility.id

  const requestClose = useCallback(() => {
    if (closing) return
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      onClose()
      return
    }
    setClosing(true)
  }, [closing, onClose])

  async function save() {
    setSaveError(null)
    try {
      await saveFacility(facility)
    } catch (error) {
      setSaveError(error instanceof Error ? error.message : 'Could not save this place.')
    }
  }

  useEffect(() => {
    closeButtonRef.current?.focus()

    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') requestClose()
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [requestClose])

  return (
    // `fixed` positions against the viewport, not the shell — so a drawer is exactly the way a
    // screen opts out of the bound MobileShell places on everything else (007 FR-001), and on a
    // desktop this one spanned all 1600px while the app behind it was 768. Centring the sheet and
    // giving it the SAME max-width as the shell puts it back inside. The dim layer stays full-bleed
    // on purpose: it is the part that should cover the screen.
    <div
      className="fixed inset-0 z-[10001] flex items-end justify-center bg-black/45"
      data-testid="detail-drawer-backdrop"
      onClick={(event) => {
        if (event.target === event.currentTarget) requestClose()
      }}
    >
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className={`${closing ? 'appearance-sheet-exit' : 'appearance-sheet-enter'} max-h-[85vh] w-full max-w-3xl overflow-y-auto rounded-t-2xl border border-primary bg-surface p-5 shadow-2xl`}
        onAnimationEnd={(event) => {
          if (closing && event.target === event.currentTarget) onClose()
        }}
      >
        <div className="mx-auto mb-4 h-1 w-12 rounded-full bg-muted" aria-hidden="true" />

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
            onClick={requestClose}
          >
            <X aria-hidden="true" size={20} />
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
            <span className="text-secondary">
              {facility.distanceMeters === null
                ? 'Distance unavailable'
                : `${Math.round(facility.distanceMeters)} m from map centre`}
            </span>
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
              className="flex min-h-11 items-center justify-center gap-2 rounded-lg border border-primary px-4 py-2 font-medium text-primary"
            >
              <Phone aria-hidden="true" size={16} />
              Call {facility.phone}
            </a>
          )}

          <button
            type="button"
            className="flex min-h-11 w-full items-center justify-center rounded-lg bg-primary px-4 py-2 font-medium text-surface disabled:cursor-not-allowed disabled:opacity-60"
            onClick={() => void save()}
            disabled={saved || saving}
          >
            {saved ? 'Saved' : saving ? 'Saving…' : 'Save place'}
          </button>
          {saveError && <p role="alert" className="text-sm text-secondary">{saveError}</p>}

          {facility.operation.notice && (
            <p className="rounded-lg bg-muted p-3 text-primary">{facility.operation.notice}</p>
          )}

          <div className="border-t border-primary pt-4">
            <p data-testid="facility-source" className="text-xs text-primary">
              <Check aria-hidden="true" className="mr-1 inline-block" size={14} />
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
