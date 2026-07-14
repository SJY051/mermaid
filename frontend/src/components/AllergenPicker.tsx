import { useEffect, useState } from 'react'
import { Button } from '@astryxdesign/core/Button'
import { CheckboxInput } from '@astryxdesign/core/CheckboxInput'

interface AllergenOption {
  key: string
  label: string
}

interface AllergenPickerProps {
  initialSelectedKeys: string[]
  onConfirm: (keys: string[]) => void
  onDismiss: () => void
}

function isAllergenOption(value: unknown): value is AllergenOption {
  if (!value || typeof value !== 'object') return false
  const option = value as Partial<AllergenOption>
  return typeof option.key === 'string' && typeof option.label === 'string'
}

export function AllergenPicker({
  initialSelectedKeys,
  onConfirm,
  onDismiss,
}: AllergenPickerProps) {
  const [options, setOptions] = useState<AllergenOption[] | null>(null)
  const [selectedKeys, setSelectedKeys] = useState(() => new Set(initialSelectedKeys))

  useEffect(() => {
    let active = true

    async function loadOptions() {
      try {
        const response = await fetch('/api/v1/ingredients/allergen-options')
        if (!response.ok) return

        const body: unknown = await response.json()
        if (!Array.isArray(body) || !body.every(isAllergenOption)) return

        const availableKeys = new Set(body.map((option) => option.key))
        if (active) {
          setSelectedKeys(
            (current) => new Set([...current].filter((key) => availableKeys.has(key))),
          )
          setOptions(body)
        }
      } catch {
        // The server-authored clarification already tells the user to see a pharmacist.
        // Rendering guessed options or a second error would make that fallback less clear.
      }
    }

    void loadOptions()
    return () => {
      active = false
    }
  }, [])

  if (!options?.length) return null

  function setSelected(key: string, checked: boolean) {
    setSelectedKeys((current) => {
      const next = new Set(current)
      if (checked) next.add(key)
      else next.delete(key)
      return next
    })
  }

  return (
    <section
      role="dialog"
      aria-labelledby="allergen-picker-title"
      className="mx-3 rounded border border-primary bg-surface p-4 shadow"
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-col gap-1">
          <h2 id="allergen-picker-title" className="text-base font-semibold text-primary">
            Tell us your allergy — pick the exact ingredient
          </h2>
          <p className="text-sm text-secondary">
            Choose every ingredient you need us to avoid, then ask your question again.
          </p>
        </div>

        <div className="flex flex-col gap-2">
          {options.map((option) => (
            <CheckboxInput
              key={option.key}
              label={option.label}
              value={selectedKeys.has(option.key)}
              onChange={(checked) => setSelected(option.key, checked)}
              width="100%"
            />
          ))}
        </div>

        <div className="flex flex-wrap gap-2">
          <Button
            label="Use selected allergies"
            variant="primary"
            isDisabled={selectedKeys.size === 0}
            onClick={() =>
              onConfirm(
                options.flatMap((option) => (selectedKeys.has(option.key) ? [option.key] : [])),
              )
            }
          />
          <Button label="My allergy isn't listed" variant="secondary" onClick={onDismiss} />
        </div>
      </div>
    </section>
  )
}
