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
    // The app is a handheld at every size it is looked at (spec 007 FR-001). It is fluid from a
    // 320px phone to a 768px tablet and stops there: a 1600px line of body text is unreadable, and
    // every tap target the wireframe placed assumes a hand, not a desk. The bound lives here, on the
    // shell, so no screen can opt out of it by accident — including the tab bar and the disclaimer,
    // which would otherwise stretch across a monitor and stop reading as a phone at all.
    <div lang="en" className="flex h-full justify-center bg-muted">
      <div
        data-testid="app-shell"
        className="flex h-full w-full max-w-3xl flex-col border-primary bg-surface md:border-x"
      >
        {/* No scroll on this wrapper. The SECTIONS scroll, one box each, and that is what makes the
            comment below true: a single shared container would carry one tab's scroll position over
            to the next, so opening Map after reading a long answer would drop you halfway down a map.
            #78 introduced that regression while adding the bound above, and #77 kept the per-section
            boxes — both halves of this conflict were right about their own half. */}
        <div className="min-h-0 flex-1">
        {/* Keep every screen mounted so each tab retains its own scroll and interaction state. */}
        <section
          className="h-full overflow-y-auto"
          aria-label="Chat screen"
          hidden={activeTab !== 'chat'}
        >
          <ChatScreen />
        </section>
        <section
          className="h-full overflow-y-auto"
          aria-label="Map screen"
          hidden={activeTab !== 'map'}
        >
          {/* active gates the location prompt and facility fetch: the screen stays mounted for
              scroll state, but must not spend the pharmacy quota until the user opens the tab. */}
          <MapScreen active={activeTab === 'map'} />
        </section>
        {/* `active` matters as much as the scroll box: SavedScreen refreshes the profile when it
            becomes active, and mounting it eagerly would send that request before anyone opened the
            tab. Both sides of this merge were right about their own half. */}
        <section
          className="h-full overflow-y-auto"
          aria-label="Saved screen"
          hidden={activeTab !== 'saved'}
        >
          <SavedScreen active={activeTab === 'saved'} />
        </section>
        <section
          className="h-full overflow-y-auto"
          aria-label="Settings screen"
          hidden={activeTab !== 'settings'}
        >
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
    </div>
  )
}
