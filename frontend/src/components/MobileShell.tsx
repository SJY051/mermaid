import { useState } from 'react'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { ChatProvider, useChatSession } from '../lib/chatSession'
import { FavoritesProvider } from '../lib/favorites'
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
  return (
    <ChatProvider>
      <FavoritesProvider>
        <MobileShellContent />
      </FavoritesProvider>
    </ChatProvider>
  )
}

function MobileShellContent() {
  const [activeTab, setActiveTab] = useState<TabId>('chat')
  const { streaming, emergencyActive, latestAnswer } = useChatSession()

  return (
    <div lang="en" className="flex h-full flex-col">
      <div className="min-h-0 flex-1 overflow-y-auto">
        {/* Keep every screen mounted so each tab retains its own scroll and interaction state. */}
        <section aria-label="Chat screen" hidden={activeTab !== 'chat'}>
          <ChatScreen />
        </section>
        <section aria-label="Map screen" hidden={activeTab !== 'map'}>
          {/* active gates the location prompt and facility fetch: the screen stays mounted for
              scroll state, but must not spend the pharmacy quota until the user opens the tab. */}
          <MapScreen active={activeTab === 'map'} />
        </section>
        <section aria-label="Saved screen" hidden={activeTab !== 'saved'}>
          <SavedScreen active={activeTab === 'saved'} />
        </section>
        <section aria-label="Settings screen" hidden={activeTab !== 'settings'}>
          <SettingsScreen />
        </section>
      </div>
      {/* An emergency gated on the active tab would route around §2-4. Do not switch tabs
          automatically: stealing the screen is hostile, while one tap back is honest. */}
      {emergencyActive && activeTab !== 'chat' && latestAnswer && (
        <div className="border-t border-primary p-3">
          <Banner
            status="error"
            data-testid="shell-emergency-alert"
            title={latestAnswer.urgency.title}
            description={
              <div className="flex flex-col gap-1">
                <span>{latestAnswer.urgency.message}</span>
                <a className="font-semibold text-primary underline" href="tel:119">
                  Call 119
                </a>
              </div>
            }
            endContent={
              <Button label="Open Chat" variant="secondary" onClick={() => setActiveTab('chat')} />
            }
          />
        </div>
      )}
      <DisclaimerStrip />
      <TabBar active={activeTab} onSelect={setActiveTab} chatBusy={streaming} />
    </div>
  )
}
