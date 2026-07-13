/**
 * Shows the settings information architecture without implying unfinished controls work.
 */
export function SettingsScreen() {
  return (
    <div className="flex flex-col gap-6 p-6">
      <h1 className="text-2xl font-semibold text-primary">Settings</h1>

      <section>
        <h2 className="text-lg font-medium text-primary">Appearance</h2>
        {/* Theme-neutral 0.1.4 ships no dark tokens — spec §5 resolution 2026-07-13. */}
        <p className="text-primary">Dark mode follows your device for now.</p>
      </section>

      <section>
        <h2 className="text-lg font-medium text-primary">Language</h2>
        {/* Korean comes later under a separate specification. */}
        <p className="text-primary">English</p>
      </section>

      {/* Allergy settings land in step 5, with §2-5-reviewed privacy copy. */}

      <section>
        <h2 className="text-lg font-medium text-primary">About</h2>
        <p className="text-primary">
          Sources: <span lang="ko">식약처</span> · <span lang="ko">심평원</span> ·{' '}
          <span lang="ko">국립중앙의료원</span>. This app informs — it never diagnoses.
        </p>
      </section>
    </div>
  )
}
