import { StrictMode, useLayoutEffect, useSyncExternalStore } from 'react'
import { createRoot } from 'react-dom/client'
import { Theme } from '@astryxdesign/core/theme'
import { neutralTheme } from '@astryxdesign/theme-neutral/built'
import './index.css'
import { MobileShell } from './components/MobileShell'
import {
  applyAppearancePreference,
  getAppearancePreference,
  subscribeAppearancePreference,
  type AppearancePreference,
} from './lib/storage'

export function MermaidApp() {
  const appearance = useSyncExternalStore(
    subscribeAppearancePreference,
    getAppearancePreference,
    (): AppearancePreference => 'device',
  )

  useLayoutEffect(() => {
    applyAppearancePreference(appearance)
  }, [appearance])

  return (
    <Theme theme={neutralTheme} mode={appearance === 'device' ? 'system' : appearance}>
      <MobileShell />
    </Theme>
  )
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <MermaidApp />
  </StrictMode>,
)
