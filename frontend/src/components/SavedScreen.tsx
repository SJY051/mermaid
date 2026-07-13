/**
 * Reserves the on-device saved-places surface for its dedicated migration step.
 */
export function SavedScreen() {
  return (
    <div className="flex flex-col gap-2 p-6">
      <h1 className="text-2xl font-semibold text-primary">Saved</h1>
      <p className="text-primary">Your saved places will appear here. They stay on this device.</p>
      {/* TODO(DEV-310 step 5): render saved facilities in this screen. */}
    </div>
  )
}
