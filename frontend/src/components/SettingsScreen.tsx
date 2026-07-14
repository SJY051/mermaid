import { Card } from '@astryxdesign/core/Card'
import { Switch } from '@astryxdesign/core/Switch'

/**
 * Shows only settings whose behavior is currently defined.
 */
export function SettingsScreen() {
  return (
    <div className="flex min-h-full flex-col gap-3 bg-muted p-4">
      <h1 className="text-xl font-semibold text-primary">Settings</h1>

      <Card width="100%">
        {/* Theme-neutral 0.1.4 ships no dark tokens — spec §5 resolution 2026-07-13. */}
        <div className="flex items-center justify-between gap-4">
          <section className="flex flex-col gap-1">
            <h2 className="font-medium text-primary">Appearance</h2>
            <p className="text-sm text-secondary">
              Follows your device. Manual appearance controls are not available yet.
            </p>
          </section>
          <Switch
            label="Appearance"
            isLabelHidden
            value={false}
            isDisabled
            disabledMessage="Manual appearance controls are not available yet."
          />
        </div>
      </Card>

      <Card width="100%">
        <section className="flex flex-col gap-1">
          <div className="flex items-center justify-between gap-4">
            <h2 className="font-medium text-primary">Language</h2>
            <span className="text-sm text-secondary">English</span>
          </div>
          <p className="text-sm text-secondary">
            <span lang="ko">한국어</span> — coming later.
          </p>
        </section>
      </Card>

      {/* TODO(DEV-56x) */}
      <Card width="100%">
        <section className="flex flex-col gap-1">
          <h2 className="font-medium text-primary">Allergy profile</h2>
          <p className="text-sm text-primary">
            Coming later. Allergy profile and consent controls are not available yet.
          </p>
        </section>
      </Card>

      <Card width="100%">
        <section className="flex flex-col gap-1">
          <h2 className="font-medium text-primary">About</h2>
          <p className="text-sm leading-relaxed text-secondary">
            Sources: <span lang="ko">식약처</span> · <span lang="ko">심평원</span> ·{' '}
            <span lang="ko">국립중앙의료원</span>. This app informs — it never diagnoses.
          </p>
        </section>
      </Card>
    </div>
  )
}
