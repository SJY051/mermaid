import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { DestructiveConsentDialog } from './DestructiveConsentDialog'

beforeEach(() => {
  HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) {
    this.setAttribute('open', '')
  })
  HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
    this.removeAttribute('open')
  })
})

function renderDialog({
  onConfirm = vi.fn(),
  onCancel = vi.fn(),
}: {
  onConfirm?: () => void
  onCancel?: () => void
} = {}) {
  render(
    <DestructiveConsentDialog
      isOpen
      title="Forget your allergy list?"
      itemNames={['ibuprofen', 'aspirin']}
      fallbackItemName="your allergy list"
      location="this device"
      recoveryMessage="You can tell us again at any time."
      onConfirm={onConfirm}
      onCancel={onCancel}
    />,
  )
}

describe('DestructiveConsentDialog', () => {
  it('names exactly what will be deleted, where it lives, and how it can be provided again', () => {
    renderDialog()

    expect(
      screen.getByText(
        'We will delete ibuprofen and aspirin from this device. You can tell us again at any time.',
      ),
    ).toBeInTheDocument()
  })

  it('cancels without performing the destructive action', () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    renderDialog({ onConfirm, onCancel })

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(onCancel).toHaveBeenCalledOnce()
    expect(onConfirm).not.toHaveBeenCalled()
  })

  it('performs the action only after confirmation', () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    renderDialog({ onConfirm, onCancel })

    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))

    expect(onConfirm).toHaveBeenCalledOnce()
    expect(onCancel).not.toHaveBeenCalled()
  })

  it('exposes the title and consequence through an alertdialog', () => {
    renderDialog()

    expect(
      screen.getByRole('alertdialog', {
        name: 'Forget your allergy list?',
        description:
          'We will delete ibuprofen and aspirin from this device. You can tell us again at any time.',
      }),
    ).toBeInTheDocument()
  })
})
