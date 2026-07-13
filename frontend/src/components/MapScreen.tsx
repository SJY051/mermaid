/**
 * Reserves the map tab without moving the existing facility flow prematurely.
 */
export function MapScreen() {
  return (
    <div className="flex flex-col gap-2 p-6">
      <h1 className="text-2xl font-semibold text-primary">Map</h1>
      <p className="text-primary">Nearby pharmacies and hospitals will appear here.</p>
      {/* TODO(DEV-310 step 3): move the facility map into this screen. */}
    </div>
  )
}
