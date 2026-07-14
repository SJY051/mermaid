import { useEffect, useState } from 'react'
import { Button } from '@astryxdesign/core/Button'
import { CheckboxInput } from '@astryxdesign/core/CheckboxInput'

interface AllergenOption {
  key: string
  label: string
}

interface AllergenPickerProps {
  initialSelectedKeys: string[]
  initialUnverifiedAllergens: string[]
  onConfirm: (keys: string[], unverified: string[]) => void
  onDismiss: () => void
}

function isAllergenOption(value: unknown): value is AllergenOption {
  if (!value || typeof value !== 'object') return false
  const option = value as Partial<AllergenOption>
  return typeof option.key === 'string' && typeof option.label === 'string'
}

export function AllergenPicker({
  initialSelectedKeys,
  initialUnverifiedAllergens,
  onConfirm,
  onDismiss,
}: AllergenPickerProps) {
  const [options, setOptions] = useState<AllergenOption[] | null>(null)
  const [selectedKeys, setSelectedKeys] = useState(() => new Set(initialSelectedKeys))
  const [unverifiedAllergens, setUnverifiedAllergens] = useState(initialUnverifiedAllergens)
  const [typedAllergen, setTypedAllergen] = useState('')

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
  const availableOptions = options

  function setSelected(key: string, checked: boolean) {
    setSelectedKeys((current) => {
      const next = new Set(current)
      if (checked) next.add(key)
      else next.delete(key)
      return next
    })
  }

  function addTypedAllergen() {
    const typed = typedAllergen.trim()
    if (!typed) return

    const folded = typed.toLocaleLowerCase()
    const resolved = availableOptions.find(
      (option) =>
        option.key.toLocaleLowerCase() === folded || option.label.toLocaleLowerCase() === folded,
    )
    if (resolved) {
      setSelected(resolved.key, true)
    } else {
      setUnverifiedAllergens((current) =>
        current.some((item) => item.toLocaleLowerCase() === folded) ? current : [...current, typed],
      )
    }
    setTypedAllergen('')
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
          {availableOptions.map((option) => (
            <CheckboxInput
              key={option.key}
              label={option.label}
              value={selectedKeys.has(option.key)}
              onChange={(checked) => setSelected(option.key, checked)}
              width="100%"
            />
          ))}
        </div>

        <div className="flex flex-col gap-2">
          <label htmlFor="unlisted-allergen" className="text-sm font-medium text-primary">
            Allergy name
          </label>
          <div className="flex flex-wrap gap-2">
            <input
              id="unlisted-allergen"
              list="allergen-options"
              className="min-h-11 flex-1 rounded border border-primary bg-surface px-3 text-primary"
              value={typedAllergen}
              onChange={(event) => setTypedAllergen(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  event.preventDefault()
                  addTypedAllergen()
                }
              }}
            />
            <datalist id="allergen-options">
              {availableOptions.map((option) => (
                <option key={option.key} value={option.label} />
              ))}
            </datalist>
            <Button
              label="Add allergy"
              variant="secondary"
              isDisabled={!typedAllergen.trim()}
              onClick={addTypedAllergen}
            />
          </div>
          <p className="text-xs text-secondary">
            Names outside this list can produce name-match warnings only; a pharmacist can fully
            check them.
          </p>
        </div>

        {unverifiedAllergens.length > 0 && (
          <div className="flex flex-wrap gap-2" aria-label="Unverified allergies">
            {unverifiedAllergens.map((allergen) => (
              <span
                key={allergen.toLocaleLowerCase()}
                className="inline-flex items-center gap-2 rounded border border-primary bg-surface px-3 py-2 text-sm text-primary"
              >
                <span>
                  {allergen} — name-match warnings only — a pharmacist can fully check this one
                </span>
                <button
                  type="button"
                  aria-label={`Remove ${allergen}`}
                  className="min-h-8 min-w-8 rounded border border-primary"
                  onClick={() =>
                    setUnverifiedAllergens((current) =>
                      current.filter((item) => item !== allergen),
                    )
                  }
                >
                  ×
                </button>
              </span>
            ))}
          </div>
        )}

        <div className="flex flex-wrap gap-2">
          <Button
            label="Use selected allergies"
            variant="primary"
            isDisabled={selectedKeys.size === 0 && unverifiedAllergens.length === 0}
            onClick={() =>
              onConfirm(
                availableOptions.flatMap((option) =>
                  selectedKeys.has(option.key) ? [option.key] : [],
                ),
                unverifiedAllergens,
              )
            }
          />
          <Button label="Cancel" variant="secondary" onClick={onDismiss} />
        </div>
      </div>
    </section>
  )
}
