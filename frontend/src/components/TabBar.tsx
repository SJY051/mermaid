import { HStack } from '@astryxdesign/core/HStack'
import type { TabId } from './MobileShell'

const TABS: { id: TabId; label: string }[] = [
  { id: 'chat', label: 'Chat' },
  { id: 'map', label: 'Map' },
  { id: 'saved', label: 'Saved' },
  { id: 'settings', label: 'Settings' },
]

/**
 * Native buttons preserve keyboard activation while the Astryx stack owns layout.
 */
export function TabBar({
  active,
  onSelect,
  chatBusy = false,
}: {
  active: TabId
  onSelect: (tab: TabId) => void
  chatBusy?: boolean
}) {
  return (
    <nav aria-label="Main" className="border-t border-primary bg-surface">
      <HStack>
        {/* Spec §3: pins and tabs must be real buttons because role="button" divs do not
            activate on Enter or Space. */}
        {TABS.map((tab) => {
          const showBusy = tab.id === 'chat' && chatBusy
          return (
            <button
              key={tab.id}
              type="button"
              aria-label={showBusy ? 'Chat — answer in progress' : tab.label}
              aria-current={active === tab.id ? 'page' : undefined}
              className="flex-1 px-3 text-sm text-primary"
              style={{ minHeight: 44 }}
              onClick={() => onSelect(tab.id)}
            >
              {tab.label}
              {/* The user who switched tabs mid-answer must be able to see the answer is still
                  coming (spec §6 step 2). */}
              {showBusy && (
                <span
                  aria-hidden="true"
                  className="ml-1 inline-block h-2 w-2 rounded-full bg-current"
                />
              )}
            </button>
          )
        })}
      </HStack>
    </nav>
  )
}
