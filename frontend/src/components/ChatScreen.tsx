import App from '../App'

/**
 * Preserves the walking skeleton intact until its chat-specific migration step.
 */
export function ChatScreen() {
  return (
    <>
      {/* TODO(DEV-310 step 2): dissolve App into this screen with the astryx Chat kit; until
          then the shell wraps it whole, so the Chat tab temporarily shows App's own banner
          above the shell strip — a safe-direction duplication. */}
      <App />
    </>
  )
}
