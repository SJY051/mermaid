/**
 * Mirrors the backend's `MermAidAnswerV1` contract (spec §5-4).
 *
 * Keep in step with `backend/src/main/java/com/mermaid/chat/dto/`. If the two drift,
 * the UI binds to fields that never arrive.
 */

export type UrgencyLevel = 'emergency' | 'urgent' | 'routine' | 'unknown'
export type DataStatus = 'live' | 'fixture' | 'mixed' | 'unavailable'
export type DataMode = 'live' | 'fixture'
export type FacilityType = 'pharmacy' | 'hospital' | 'emergency_room'
export type PrescriptionStatus = 'prescription' | 'otc' | 'unknown'

/**
 * Four states, not a nullable warning (spec §2-12).
 *
 * `no_match_found` means "we did not find a match in the ingredient list we have".
 * It does NOT mean the drug is safe. Never render it as reassurance.
 */
export type AllergyStatus = 'blocked' | 'warning' | 'no_match_found' | 'unknown'

export interface AllergyCheck {
  status: AllergyStatus
  matchedIngredients: string[]
  message: string
}

/** Where a fact came from. Every fact card carries one (spec §2-14). */
export interface SourceRef {
  id: string
  provider: string
  recordId: string | null
  retrievedAt: string
  dataMode: DataMode
  title: string
}

export interface Ingredient {
  nameKo: string | null
  nameEn: string | null
  normalizedKey: string | null
  amount: number | string | null
  unit: string | null
}

export interface DrugCard {
  id: string
  productNameKo: string
  productNameEn: string | null
  ingredients: Ingredient[]
  indicationSummary: string | null
  directionsSummary: string | null
  /** Includes DUR contraindications (spec §2-10). */
  warnings: string[]
  prescriptionStatus: PrescriptionStatus
  allergyCheck: AllergyCheck
  sourceRefId: string
}

/**
 * What the assistant asks the UI to do. An allowlist, not free-form (spec §2-11).
 *
 * These are fields in the response, not provider tool calls: a tool-call message has
 * empty `content` and so cannot also carry schema-constrained JSON.
 */
export type UiAction =
  | { type: 'OPEN_FACILITY_MAP'; payload: { types: FacilityType[]; openNow: boolean; radiusM: number } }
  | { type: 'APPLY_FACILITY_FILTERS'; payload: { types: FacilityType[]; openNow: boolean; radiusM: number } }
  | { type: 'OPEN_DRUG_DETAIL'; payload: { drugId: string } }
  | { type: 'SHOW_EMERGENCY_CALL'; payload: { phone: string; label: string } }
  | { type: 'ASK_CLARIFYING_QUESTION'; payload: { question: string } }

export interface Urgency {
  level: UrgencyLevel
  title: string
  message: string
  reasonCodes: string[]
  actions: UiAction[]
}

export interface MermAidAnswer {
  schemaVersion: '1.0'
  answerId: string
  language: 'en'
  dataStatus: DataStatus
  urgency: Urgency
  summary: string
  clarifyingQuestions: string[]
  guidance: Array<{
    id: string
    title: string
    body: string
    evidence: 'official_data' | 'general_safety' | 'model_summary'
    sourceRefIds: string[]
  }>
  drugs: DrugCard[]
  uiActions: UiAction[]
  sourceRefs: SourceRef[]
  warnings: string[]
  /** Always present (SA-02). Render it. */
  disclaimer: string
}

// ---------------------------------------------------------------------------
// GET /api/v1/facilities
// ---------------------------------------------------------------------------

export type OperationStatus = 'open' | 'closed' | 'unknown'
export type StatusConfidence = 'official_realtime' | 'official_schedule' | 'inferred' | 'unknown'

export interface FacilityOperation {
  /** null means "we could not determine it" — never render null as "closed" (spec §2-13). */
  isOpenNow: boolean | null
  status: OperationStatus
  statusConfidence: StatusConfidence
  verifiedAt: string | null
  notice: string
}

export interface Facility {
  /** Provider-namespaced: `facility:nmc:12345` (spec §4-3). */
  id: string
  type: FacilityType
  nameKo: string
  nameEn: string | null
  addressKo: string | null
  addressEn: string | null
  phone: string | null
  latitude: number
  longitude: number
  /** Computed by the backend — no public API provides it. */
  distanceMeters: number
  operation: FacilityOperation
  source: SourceRef
}

// ---------------------------------------------------------------------------
// Errors — mirrors backend `ErrorCode` and `GlobalExceptionHandler` (spec §5-2)
// ---------------------------------------------------------------------------

/**
 * Switch on `code`, never on `message`. The message is written for a person and may change;
 * the code is the contract.
 */
export type ErrorCode =
  | 'INVALID_REQUEST'
  | 'INPUT_TOO_LARGE'
  | 'UNSUPPORTED_MODEL'
  | 'RATE_LIMITED'
  | 'LOCATION_REQUIRED'
  | 'RESOURCE_NOT_FOUND'
  | 'AI_PROVIDER_TIMEOUT'
  | 'AI_PROVIDER_ERROR'
  | 'AI_SCHEMA_INVALID'
  | 'FACILITY_PROVIDER_TIMEOUT'
  | 'DRUG_PROVIDER_TIMEOUT'
  | 'SOURCE_UNAVAILABLE'
  | 'SOURCE_PAYLOAD_INVALID'
  | 'NOT_IMPLEMENTED'
  | 'INTERNAL_ERROR'

export interface ApiError {
  error: {
    code: ErrorCode
    /** Safe to render. Never contains internals. */
    message: string
    /** Whether offering a "try again" button is honest. */
    retryable: boolean
    /** Also on the `X-Request-Id` response header. Ask the user for this when they report a bug. */
    request_id: string
    details?: Record<string, unknown>
  }
}

export function isApiError(value: unknown): value is ApiError {
  return (
    typeof value === 'object' &&
    value !== null &&
    'error' in value &&
    typeof (value as ApiError).error?.code === 'string'
  )
}
