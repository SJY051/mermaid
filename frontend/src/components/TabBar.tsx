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
}: {
  active: TabId
  onSelect: (tab: TabId) => void
}) {
  return (
    <nav aria-label="Main" className="border-t border-primary bg-surface">
      <HStack>
        {/* Spec §3: pins and tabs must be real buttons because role="button" divs do not
            activate on Enter or Space. */}
        {TABS.map((tab) => (
          <button
            key={tab.id}
            type="button"
            aria-current={active === tab.id ? 'page' : undefined}
            className="flex-1 px-3 text-sm text-primary"
            style={{ minHeight: 44 }}
            onClick={() => onSelect(tab.id)}
          >
            {tab.label}
          </button>
        ))}
      </HStack>
    </nav>
  )
}
