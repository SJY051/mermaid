import { AlertDialog } from '@astryxdesign/core/AlertDialog'

interface DestructiveConsentDialogProps {
  isOpen: boolean
  title: string
  itemNames: readonly string[]
  fallbackItemName: string
  location: string
  recoveryMessage: string
  confirmLabel?: string
  cancelLabel?: string
  onConfirm: () => void
  onCancel: () => void
}

function formatItemNames(itemNames: readonly string[], fallbackItemName: string) {
  if (itemNames.length === 0) return fallbackItemName
  if (itemNames.length === 1) return itemNames[0]
  if (itemNames.length === 2) return `${itemNames[0]} and ${itemNames[1]}`

  return `${itemNames.slice(0, -1).join(', ')}, and ${itemNames.at(-1)}`
}

/**
 * Names the destructive action's target, storage location, and recovery path before consent.
 */
export function DestructiveConsentDialog({
  isOpen,
  title,
  itemNames,
  fallbackItemName,
  location,
  recoveryMessage,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  onConfirm,
  onCancel,
}: DestructiveConsentDialogProps) {
  const items = formatItemNames(itemNames, fallbackItemName)
  const description = `We will delete ${items} from ${location}. ${recoveryMessage}`

  return (
    <AlertDialog
      isOpen={isOpen}
      title={title}
      description={description}
      actionLabel={confirmLabel}
      cancelLabel={cancelLabel}
      onAction={onConfirm}
      onOpenChange={(open) => {
        if (!open) onCancel()
      }}
    />
  )
}
