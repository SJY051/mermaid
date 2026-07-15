import { HStack } from '@astryxdesign/core/HStack'
import {
  Bookmark,
  MapPin,
  MessageCircle,
  Settings,
  type LucideIcon,
} from 'lucide-react'
import type { TabId } from './MobileShell'

const TABS: { id: TabId; label: string; icon: LucideIcon }[] = [
  { id: 'chat', label: 'Chat', icon: MessageCircle },
  { id: 'map', label: 'Map', icon: MapPin },
  { id: 'saved', label: 'Saved', icon: Bookmark },
  { id: 'settings', label: 'Settings', icon: Settings },
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
          const activeTab = active === tab.id
          const Icon = tab.icon
          return (
            <button
              key={tab.id}
              type="button"
              aria-label={showBusy ? 'Chat — answer in progress' : tab.label}
              aria-current={activeTab ? 'page' : undefined}
              className={`appearance-tab-transition flex min-h-[53px] flex-1 flex-col items-center justify-center gap-[3px] px-3 text-sm ${
                activeTab ? 'font-bold text-primary' : 'text-secondary'
              }`}
              onClick={() => onSelect(tab.id)}
            >
              <Icon aria-hidden="true" size={19} />
              <span className="inline-flex items-center">
                {tab.label}
                {/* The user who switched tabs mid-answer must be able to see the answer is still
                    coming (spec §6 step 2). */}
                {showBusy && (
                  <span
                    aria-hidden="true"
                    className="ml-1 inline-block h-2 w-2 rounded-full bg-current"
                  />
                )}
              </span>
            </button>
          )
        })}
      </HStack>
    </nav>
  )
}
