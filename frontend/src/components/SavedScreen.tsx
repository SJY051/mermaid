import { useEffect, useState } from 'react'
import { useFavorites } from '../lib/favorites'
import type { SavedFacility } from '../lib/storage'

export interface SavedScreenProps {
  active: boolean
}

function typeLabel(type: string): string {
  if (type === 'pharmacy') return 'Pharmacy'
  if (type === 'hospital') return 'Hospital'
  return 'Emergency room'
}

/**
 * What a saved place can honestly say about its hours: nothing current.
 *
 * <p>The snapshot was true at the moment it was saved and has not been checked since — opening this
 * tab reloads the alias and the note from the profile, never the facility's hours. Rendering that
 * stored `isOpenNow` as "Open now" tells someone at 11pm that a pharmacy they saved last Tuesday
 * afternoon is open, and sends them out to a locked door. The same reasoning as §2-3, one step
 * further along: there, an unknown state must not be drawn as "Closed"; here, a *stale* state must
 * not be drawn as anything at all. The date is the honest thing we hold, so the date is what it says.
 */
function savedOnLabel(retrievedAt: string): string {
  const date = retrievedAt.slice(0, 10)
  return `Hours not checked since ${date} — open it on the Map to see if it is open now.`
}

export function SavedScreen({ active }: SavedScreenProps) {
  const { favorites, refreshFavorites, updateFavorite, removeFavorite } = useFavorites()
  const [editing, setEditing] = useState<SavedFacility | null>(null)
  const [alias, setAlias] = useState('')
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (!active) return
    setLoading(true)
    setError(null)
    void refreshFavorites()
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : 'Could not load saved places.'))
      .finally(() => setLoading(false))
  }, [active, refreshFavorites])

  function beginEdit(favorite: SavedFacility) {
    setEditing(favorite)
    setAlias(favorite.alias)
    setNote(favorite.note)
    setError(null)
  }

  async function submitEdit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!editing) return
    setSubmitting(true)
    setError(null)
    try {
      await updateFavorite(editing, alias, note)
      setEditing(null)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not update this saved place.')
    } finally {
      setSubmitting(false)
    }
  }

  async function remove(favorite: SavedFacility) {
    setSubmitting(true)
    setError(null)
    try {
      await removeFavorite(favorite)
      if (editing?.id === favorite.id) setEditing(null)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not remove this saved place.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="flex flex-col gap-4 p-6">
      <div>
        <h1 className="text-2xl font-semibold text-primary">Saved</h1>
        <p className="mt-1 text-sm text-secondary">Saved places are stored in your anonymous profile. This device keeps a display copy.</p>
      </div>

      {loading && <p className="text-sm text-secondary">Loading saved places…</p>}
      {error && <p role="alert" className="text-sm text-secondary">{error}</p>}

      {!loading && favorites.length === 0 && (
        <p className="text-sm text-secondary">No saved places yet. Save a place from its details on the Map tab.</p>
      )}

      <ul className="space-y-3" aria-label="Saved places">
        {favorites.map((favorite) => (
          <li key={favorite.id} className="rounded-lg border border-primary p-4">
            {editing?.id === favorite.id ? (
              <form className="space-y-3" onSubmit={(event) => void submitEdit(event)}>
                <label className="block text-sm font-medium text-primary">
                  Name
                  <input
                    value={alias}
                    onChange={(event) => setAlias(event.target.value)}
                    maxLength={100}
                    className="mt-1 min-h-11 w-full rounded border border-primary bg-surface px-3 text-primary"
                  />
                </label>
                <label className="block text-sm font-medium text-primary">
                  Note
                  <textarea
                    value={note}
                    onChange={(event) => setNote(event.target.value)}
                    maxLength={500}
                    rows={3}
                    className="mt-1 w-full rounded border border-primary bg-surface px-3 py-2 text-primary"
                  />
                </label>
                <div className="flex gap-2">
                  <button type="submit" disabled={submitting} className="min-h-11 rounded bg-primary px-3 font-medium text-inverse">Save changes</button>
                  <button type="button" disabled={submitting} onClick={() => setEditing(null)} className="min-h-11 rounded border border-primary px-3 text-primary">Cancel</button>
                </div>
              </form>
            ) : (
              <>
                <p className="font-semibold text-primary" lang="ko">{favorite.alias || favorite.snapshot.nameKo}</p>
                {favorite.alias && <p className="mt-1 text-sm text-secondary" lang="ko">{favorite.snapshot.nameKo}</p>}
                <p className="mt-1 text-sm text-secondary">{typeLabel(favorite.snapshot.type)}</p>
                <p className="mt-1 text-sm text-secondary">
                  {savedOnLabel(favorite.snapshot.source.retrievedAt)}
                </p>
                <p className="mt-1 text-sm text-secondary">{favorite.snapshot.addressKo ?? 'Address unavailable'}</p>
                <p className="mt-2 text-xs text-primary">
                  {favorite.snapshot.source.title} · {favorite.snapshot.source.retrievedAt.slice(0, 10)}
                </p>
                {favorite.snapshot.source.dataMode === 'fixture' && (
                  <p className="mt-1 text-xs font-medium text-primary">Sample data</p>
                )}
                {favorite.note && <p className="mt-3 text-sm text-primary">{favorite.note}</p>}
                <div className="mt-4 flex gap-2">
                  <button type="button" onClick={() => beginEdit(favorite)} className="min-h-11 rounded border border-primary px-3 text-primary">Edit</button>
                  <button type="button" disabled={submitting} onClick={() => void remove(favorite)} className="min-h-11 rounded border border-primary px-3 text-primary">Remove</button>
                </div>
              </>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}
