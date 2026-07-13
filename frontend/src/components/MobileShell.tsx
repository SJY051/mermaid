import { useState } from 'react'
import { ChatScreen } from './ChatScreen'
import { DisclaimerStrip } from './DisclaimerStrip'
import { MapScreen } from './MapScreen'
import { SavedScreen } from './SavedScreen'
import { SettingsScreen } from './SettingsScreen'
import { TabBar } from './TabBar'

export type TabId = 'chat' | 'map' | 'saved' | 'settings'

/**
 * Keeps navigation and the safety disclaimer fixed while each screen owns its scroll position.
 */
export function MobileShell() {
  const [activeTab, setActiveTab] = useState<TabId>('chat')

  return (
    <div lang="en" className="flex h-full flex-col">
      <div className="min-h-0 flex-1 overflow-y-auto">
        {/* Chat state lives in App's component state, so unmounting it on a tab switch would
            destroy the conversation. Keep every screen mounted and hide inactive screens. */}
        <section aria-label="Chat screen" hidden={activeTab !== 'chat'}>
          <ChatScreen />
        </section>
        <section aria-label="Map screen" hidden={activeTab !== 'map'}>
          <MapScreen />
        </section>
        <section aria-label="Saved screen" hidden={activeTab !== 'saved'}>
          <SavedScreen />
        </section>
        <section aria-label="Settings screen" hidden={activeTab !== 'settings'}>
          <SettingsScreen />
        </section>
      </div>
      <DisclaimerStrip />
      <TabBar active={activeTab} onSelect={setActiveTab} />
    </div>
  )
}
