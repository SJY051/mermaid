/**
 * Mirrors the backend's `MedicalResponse` (spec §5-1).
 *
 * Keep this in step with `backend/src/main/java/com/mermaid/chat/dto/MedicalResponse.java`.
 * If the two drift, the UI binds to fields that never arrive.
 */

export type Urgency = 'self_care' | 'see_pharmacist' | 'see_doctor' | 'emergency'

export type FacilityType = 'pharmacy' | 'hospital'

export interface Medication {
  koreanName: string
  englishIngredient: string
  purpose: string
  dosage: string
  cautions: string[]
  prescriptionRequired: boolean
  /** Set when the drug hits one of the profile's avoided ingredients (FR-04). */
  allergyWarning: string | null
}

/**
 * Present when the assistant wants a map shown. `null` means no map.
 *
 * This is a field rather than a tool call on purpose: a model that emits a tool call
 * leaves `content` empty, so a tool call and schema-constrained JSON can never share
 * one message. See spec §2-1.
 */
export interface MapDirective {
  type: FacilityType
  radiusMeters: number
  openNow: boolean
  reason: string
}

export interface MedicalResponse {
  reply: string
  urgency: Urgency
  medications: Medication[]
  map: MapDirective | null
  /** Always present (SA-02). Render it. */
  disclaimer: string
}

/** What `GET /api/v1/facilities` returns. */
export interface Facility {
  id: string
  type: FacilityType
  name: string
  address: string
  phone: string
  latitude: number
  longitude: number
  /** Computed by the backend — no public API provides it. */
  distanceMeters: number
  /** Computed by the backend — no public API provides it either. */
  openNow: boolean
}
